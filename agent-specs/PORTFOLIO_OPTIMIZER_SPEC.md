# Portfolio Optimizer — Complete Handover Specification

> **Purpose**: Self-contained specification for Claude Code to implement a multi-tenant, self-hosted portfolio optimization web application. This document contains all architectural decisions, algorithm specifications, library choices, functional requirements, non-functional requirements, and implementation guidance derived from extensive prior analysis.
>
> **Date**: March 15, 2026
> **Author**: Generated from collaborative design sessions between the project owner and Claude (Anthropic)

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [Portfolio Optimization Algorithms](#3-portfolio-optimization-algorithms)
4. [Technology Stack](#4-technology-stack)
5. [Database Schema](#5-database-schema)
6. [Python Optimization Microservice](#6-python-optimization-microservice)
7. [Spring Boot Application](#7-spring-boot-application)
8. [Frontend (JTE + vanilla JS + Chart.js)](#8-frontend)
9. [Market Data Pipeline](#9-market-data-pipeline)
10. [Authentication & Authorization](#10-authentication--authorization)
11. [Feature Flags](#11-feature-flags)
12. [Deployment](#12-deployment)
13. [Security Requirements](#13-security-requirements)
14. [Phase 1 Implementation Blocks](#14-phase-1-implementation-blocks)
15. [Future Phases](#15-future-phases)
16. [Testing Strategy](#16-testing-strategy)
17. [Appendix A: Mathematical Foundations](#appendix-a-mathematical-foundations)
18. [Appendix B: Example Portfolio Data](#appendix-b-example-portfolio-data)
19. [Appendix C: yfinance Ticker Mapping](#appendix-c-yfinance-ticker-mapping)

---

## 1. Project Overview

### 1.1 What This Is

A self-hosted web application that helps individual investors optimize their stock portfolios using mathematically rigorous frameworks (Black-Litterman, multi-asset Kelly criterion, CVaR, Hierarchical Risk Parity). Users create candidate portfolios, input their intrinsic value estimates and confidence levels for each position, and the system computes optimal allocations accounting for correlations between assets.

### 1.2 Key Design Principles

- **Hybrid architecture**: Spring Boot (Kotlin) for the web app; thin Python FastAPI microservice for optimization math
- **Python is stateless**: Fed all data in each REST request from Kotlin. No DB access from Python. Pure function: `(data) → (result)`
- **Server-rendered UI**: JTE 3.2.3 (`.kte` Kotlin templates) + vanilla JS + Chart.js. No SPA, no npm, no build toolchain
- **Security-first**: Nonce-based CSP (`CspNonceFilter`). No inline scripts/styles. Self-hosted JS libraries. HTTP-only session cookies
- **Client-side portfolio values**: Absolute portfolio values (shares held, cash amounts, cost basis, total value) never leave the browser. Backend stores only tickers, position types (`EQUITY`/`CASH`), percentage weights, views, and confidences. Cash positions are modeled per currency (e.g., `CASH.USD`, `CASH.JPY`) with only their weight percentage stored server-side. Discrete allocation is computed in the browser. Stale weights are detected by reconciling localStorage share counts against current prices
- **Multi-tenant**: PostgreSQL with row-level security. 10–50 concurrent users
- **Phased rollout**: Feature flags control which optimization algorithms and UI features are available

### 1.3 Users and Scale

- Initial: Invite-only, admin creates user accounts
- Later (feature-flagged): Self-registration
- 10–50 concurrent users
- Self-hosted on a single Linux server (4GB RAM, 2 vCPUs minimum)
- Free market data sources only (yfinance, Frankfurter API)

---

## 2. Architecture

### 2.1 High-Level Component Diagram

```
┌──────────────┐     HTTPS      ┌─────────────────────────────────────┐
│   Browser     │◄──────────────►│  Caddy (reverse proxy + TLS)        │
│  (vanilla JS + │                │  auto Let's Encrypt certificates    │
│   Chart.js)   │                └──────┬──────────────────────────────┘
└──────────────┘                       │ mTLS :8080
                                ┌───────▼──────────────────────────────┐
                                │  Spring Boot (Kotlin)                │
                                │  ─ JTE server-rendered HTML          │
                                │  ─ REST JSON endpoints for charts    │
                                │  ─ Spring Security (session-based)   │
                                │  ─ Spring Cloud Vault integration    │
                                │  ─ Market data caching               │
                                │  ─ Feature flags                     │
                                │  ─ Admin panel                       │
                                └──┬──────────┬──────────────┬─────────┘
                                   │          │              │
                        mTLS (PG)  │          │ mTLS :8000   │ Vault API
                                   │          │ REST (JSON)  │ :8200
                          ┌────────▼───┐  ┌───▼───────────┐  │
                          │ PostgreSQL  │  │ Python FastAPI │  │
                          │ 16         │  │ ─ PyPortfolioOpt│  │
                          │            │  │ ─ Riskfolio-Lib│  │
                          │ Tables:    │  │ ─ cvxpy        │  │
                          │ users      │  │ ─ numpy/scipy  │  │
                          │ portfolios │  │ ─ yfinance     │  │
                          │ positions  │  │                │  │
                          │ opt_runs   │  │ STATELESS      │  │
                          │ prices     │  │ No DB access   │  │
                          │ fx_rates   │  │ All data in req│  │
                          │ flags      │  └───────┬────────┘  │
                          │ invite_codes│          │ Vault API │
                          └────────────┘          │ :8200     │
                                          ┌───────▼───────────▼────────┐
                                          │  HashiCorp Vault            │
                                          │  ─ PKI: mTLS certs for all │
                                          │    inter-service links      │
                                          │  ─ Database: dynamic PG    │
                                          │    credentials              │
                                          │  ─ KV v2: SMTP, session    │
                                          │    keys, static secrets     │
                                          │                             │
                                          │  Vault Agent sidecars       │
                                          │  manage cert rotation       │
                                          └─────────────────────────────┘
```

### 2.2 Communication Pattern

1. User interacts with JTE-rendered HTML pages via vanilla JS fetch
2. For chart data, separate `fetch()` calls hit `/api/**` JSON endpoints (session-authenticated, including `/api/prices/latest`)
3. Both HTML and JSON endpoints authenticated via same Spring Security session cookie
4. All inter-service communication uses mTLS with Vault PKI-issued certificates (24h TTL, auto-renewed by Vault Agent)
5. When optimization is requested:
   a. Spring Boot loads portfolio positions (percentage weights, views, confidences) + cached price history from PostgreSQL (dynamic Vault DB credentials)
   b. Spring Boot converts intrinsic value estimates to expected returns via CAGR: `Q_k = (IV_k / P_k)^(1/5) − 1`
   c. Spring Boot assembles payload (prices matrix, percentage weights, views as Q vector, confidences, constraints) — no absolute portfolio values
   d. Spring Boot POSTs JSON payload to `https://optimizer:8000/optimize` (mTLS)
   e. Python computes optimization, returns JSON result (optimal weights + metrics, no discrete allocation)
   f. Spring Boot stores result in `optimization_runs` table (weights and metrics only)
   g. Spring Boot renders result page via JTE (weights, metrics, charts)
   h. Browser JS computes discrete allocation (integer share counts, trade list) using locally-held total portfolio value and current prices fetched from authenticated `/api/prices/latest`
6. Market data refresh runs as a scheduled job (daily at 22:00 UTC) — calls Python service endpoints `/fetch-prices` and `/fetch-fx-rates` over mTLS

### 2.3 Why Hybrid (Not Pure Java, Not Pure Python)

- **Python wins on math**: PyPortfolioOpt provides Black-Litterman with Idzorek confidence, CVaR, HRP, and Ledoit-Wolf shrinkage in ~3 lines each. Equivalent Java (ojAlgo) lacks CVaR, HRP, and Ledoit-Wolf. Custom implementations would cost 2–4 weeks.
- **Kotlin/Spring Boot wins on everything else**: Auth, CRUD, UI rendering, caching, migrations, testing — all in the developer's strongest language.
- **Python stays tiny**: ~300 lines of FastAPI code. Stateless. Independently deployable and restartable.
- **REST overhead is negligible**: ~5ms on localhost for the HTTP round-trip vs. 100–500ms for the optimization computation itself.

---

## 3. Portfolio Optimization Algorithms

### 3.1 Phase 1: Black-Litterman with Idzorek Confidence

**What it does**: Combines market equilibrium returns (what the market "thinks" each asset should return) with the user's personal views (intrinsic value estimates) and confidence levels.

**Mathematical formulation**:

1. Compute equilibrium returns: `Π = δ × Σ × w_mkt` where δ ≈ 2.5, Σ is the covariance matrix, w_mkt are market-cap weights
2. Express user views as: P (pick matrix), Q (expected returns), Ω (uncertainty)
3. Idzorek's method converts intuitive confidence (0–100%) to Ω: `ω_k² = [(1 − c_k)/c_k] × τ × p_kᵀ × Σ × p_k`
4. Posterior returns: `E[μ] = [(τΣ)⁻¹ + PᵀΩ⁻¹P]⁻¹ × [(τΣ)⁻¹Π + PᵀΩ⁻¹Q]`
5. Apply half-Kelly sizing: `f = 0.5 × Σ_posterior⁻¹ × μ_posterior`
6. Project onto constraints (position bounds, sector limits, long-only)

**User inputs per position**: Ticker, current weight (%), 5-year intrinsic value estimate (in local currency), confidence level (0–100%). Share counts and cash amounts are entered in the browser but stay in localStorage — only percentage weights reach the backend. Cash positions (per currency) are modeled as positions with `position_type = 'CASH'`.

**View computation (intrinsic value → BL Q vector)**: The user's 5-year intrinsic value estimate is converted to an annualized expected return using CAGR: `Q_k = (IV_k / P_k)^(1/5) − 1`, where `P_k` is the current price in local currency. This conversion runs in Spring Boot when assembling the optimizer payload — the Python service receives `Q` directly (not raw intrinsic values). Positions with no intrinsic value estimate are omitted from `views` and `confidences`, so BL uses equilibrium returns for those.

**System inputs**: Covariance matrix (from cached price history with Ledoit-Wolf shrinkage), market-cap weights (from yfinance), risk-free rate (from ECB data or hardcoded)

**Python library**: `pypfopt.BlackLittermanModel` with `omega="idzorek"` and `view_confidences=[0.0–1.0, ...]`

### 3.2 Phase 1: Mean-Variance (Markowitz) Efficient Frontier

**What it does**: Finds the set of portfolios offering maximum return for each level of risk. The tangency portfolio (maximum Sharpe ratio) is mathematically equivalent to full Kelly.

**Python library**: `pypfopt.EfficientFrontier` with `max_sharpe()`, `min_volatility()`, `efficient_risk(target_vol)`, `efficient_return(target_return)`

**Constraints**: `ef.add_constraint(lambda w: w >= 0.01)` (1% minimum), `ef.add_constraint(lambda w: w <= 0.08)` (8% maximum), sector constraints via custom constraint functions

### 3.3 Phase 2: Multi-Asset Kelly Criterion

**What it does**: Maximizes geometric growth rate (log wealth). The optimal allocation is: `f* = Σ⁻¹ × μ` (full Kelly). In practice, use half-Kelly: `f = 0.5 × Σ⁻¹ × μ`

**Key property**: The Kelly portfolio IS the Markowitz tangency portfolio, scaled differently. Relative weights among risky assets are identical. Only total allocation to risky assets changes.

**Implementation**: After computing BL posterior returns, apply `0.5 × Σ⁻¹ × μ` with constraints. This is a quadratic program solvable via cvxpy.

### 3.4 Phase 3: CVaR (Conditional Value-at-Risk) Optimization

**What it does**: Minimizes expected loss in the worst tail scenarios, accounting for fat tails that variance-based methods miss.

**Python library**: `pypfopt.EfficientCVaR` or `riskfolio.Portfolio` with `model='Classic'` and `rm='CVaR'`

**When to use**: As a constraint or alternative objective. User selects "minimize tail risk" vs. "maximize Sharpe" vs. "maximize geometric growth."

### 3.5 Phase 3: Hierarchical Risk Parity (HRP)

**What it does**: Clusters correlated assets, then allocates via recursive bisection. No matrix inversion required. More robust to estimation errors than Markowitz.

**Python library**: `pypfopt.HRPOpt` or `riskfolio.HCPortfolio`

**Use case**: Robustness check. Run alongside primary optimization. If HRP and BL disagree dramatically, flag for user review.

### 3.6 Covariance Estimation

**Ledoit-Wolf shrinkage** is mandatory for portfolios of 20–40 assets estimated from 2–5 years of daily data. The sample covariance matrix is ill-conditioned. Shrinkage pulls it toward a structured target.

**Python**: `pypfopt.risk_models.CovarianceShrinkage(prices, frequency=252).ledoit_wolf()`

**Input**: Daily close prices in EUR (converted from local currency), minimum 2 years, ideally 3–5 years.

### 3.7 Discrete Allocation (Browser-Side)

After the backend computes continuous optimal weights, the browser converts them to integer share counts using the user's locally-held total portfolio value and current prices.

**Runs in browser JavaScript** (not Python). See §8.5 for the algorithm.

**Privacy**: The backend never sees `total_portfolio_value` or discrete share counts. These values exist only in the user's browser.

### 3.8 Correlation Analysis

For any candidate allocation, compute **effective number of independent bets**: `N_eff = N / [1 + (N−1) × ρ̄]` where ρ̄ is average pairwise correlation within a cluster. Display this metric to users alongside optimization results.

---

## 4. Technology Stack

### 4.1 Spring Boot Application (Primary Backend)

| Component | Choice | Version | Notes |
|---|---|---|---|
| Language | Kotlin | 2.3.20 | JVM 25 |
| Framework | Spring Boot | 4.0.4 | Web, Security, Data JDBC, Actuator |
| Template Engine | JTE (jte + jte-kotlin + jte-spring-boot-starter-3) | 3.2.3 | Server-side `.kte` HTML rendering |
| Database Access | Spring Data JDBC | (bundled) | No ORM — plain SQL with Kotlin data class mapping |
| HTTP Client | Spring WebClient (WebFlux) | (bundled) | For calling Python service |
| Resilience | Resilience4j | latest | Circuit breaker for Python service calls |
| Migrations | Liquibase | latest | YAML-based changelogs |
| Build | Gradle (Kotlin DSL) | 9.4.1 | |
| Testing | JUnit 5, MockK, Testcontainers | latest | |

### 4.2 Python Optimization Microservice

| Component | Choice | Version | Notes |
|---|---|---|---|
| Language | Python | 3.13.12 | |
| Framework | FastAPI | 0.135.1 | With Uvicorn 0.42.0 ASGI server |
| Portfolio Optimization | PyPortfolioOpt | 1.5.6 | BL, MVO, HRP, CVaR, discrete allocation |
| Advanced Optimization | Riskfolio-Lib | 7.2.1 | Phase 3: CVaR variants, risk budgeting |
| Convex Solver | cvxpy | 1.8.1 | Underlying engine (auto-installed) |
| Numerics | numpy 2.4.3, scipy 1.17.1, pandas 3.0.1 | | Foundation layer |
| Validation | Pydantic | 2.12.5 | Request/response schemas |
| Testing | pytest | 9.0.2 | |

### 4.3 Frontend (No Build Tools Required)

| Component | Choice | Version | Delivery |
|---|---|---|---|
| Server Interaction | vanilla JS fetch | — | No framework; `csrf-fetch.js` handles CSRF tokens |
| Charts | Chart.js | 4.5.1 | Self-hosted: `/static/vendor/chartjs/chart.umd.min.js` |
| CSS Framework | Tailwind CSS (CDN-free) | 4.2.2 | Built via standalone CLI during Gradle build, output to `/static/css/tailwind.css` |
| Icons (optional) | Lucide | 0.577.0 | Self-hosted SVG sprites |

**CRITICAL**: All JavaScript/CSS libraries are self-hosted under `src/main/resources/static/vendor/`. No CDN references. No inline `<script>` or `<style>` tags anywhere in the codebase.

**Tailwind CSS build integration**: Tailwind 4's standalone CLI binary (no Node.js required) is invoked by a Gradle task during `processResources`. The binary is committed to the repository under `web/tools/tailwindcss` (platform-specific, gitignored in CI — downloaded on first build if missing). The Gradle task:

```kotlin
// web/build.gradle.kts
val tailwindBuild by tasks.registering(Exec::class) {
    description = "Build Tailwind CSS from source"
    val inputCss = file("src/main/resources/static/css/input.css")
    val outputCss = file("src/main/resources/static/css/tailwind.css")
    inputs.file(inputCss)
    inputs.files(fileTree("src/main/resources/templates") { include("**/*.html") })
    outputs.file(outputCss)
    commandLine("./tools/tailwindcss", "-i", inputCss.path, "-o", outputCss.path, "--minify")
}

tasks.named("processResources") {
    dependsOn(tailwindBuild)
}
```

The `input.css` file contains `@import "tailwindcss"` and any custom CSS. Template HTML files are scanned for class names automatically by Tailwind 4. The output `tailwind.css` is generated fresh on each build; it is gitignored (not committed).

### 4.4 Infrastructure

| Component | Choice | Notes |
|---|---|---|
| Database | PostgreSQL 18 | Docker container |
| Reverse Proxy | Caddy 2 | Auto TLS via Let's Encrypt |
| Secrets Management | HashiCorp Vault 1.19 | Dynamic DB credentials, mTLS PKI, KV for static secrets |
| Container Runtime | Docker Compose | 6 services: vault, vault-init, app, optimizer, postgres, caddy |
| Backup | pg_dump + cron + rclone | Nightly to off-site storage, Vault-issued DB creds |

---

## 5. Database Schema

### 5.1 Documentation Convention

All tables and columns use PostgreSQL's `COMMENT ON` feature for persistent, queryable documentation. During implementation, the inline `--` comments in the SQL below are translated to `COMMENT ON TABLE` / `COMMENT ON COLUMN` statements in the Liquibase changesets. This keeps field semantics discoverable via `\d+` in psql or any SQL client, independent of application code.

### 5.2 Core Tables

```sql
-- Liquibase changeset: 001-initial-schema

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Users
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(50) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',  -- 'USER' or 'ADMIN'
    enabled BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Invite codes (for invite-only registration)
CREATE TABLE invite_codes (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code VARCHAR(20) UNIQUE NOT NULL,
    created_by UUID NOT NULL REFERENCES users(id),
    used_by UUID REFERENCES users(id),
    used_at TIMESTAMPTZ,
    expires_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Portfolios (users can have multiple candidate portfolios)
CREATE TABLE portfolios (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    base_currency CHAR(3) NOT NULL DEFAULT 'EUR',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Positions within a portfolio
-- Absolute values (share counts, cash amounts, total value) stay in the browser.
-- Backend stores only percentage weights and cost basis as a portfolio fraction.
-- Both equity and cash positions are modeled here. Cash positions use synthetic
-- tickers (e.g., "CASH.USD", "CASH.JPY") and have position_type = 'CASH'.
CREATE TABLE positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id UUID NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    position_type VARCHAR(10) NOT NULL DEFAULT 'EQUITY',  -- 'EQUITY' or 'CASH'
    ticker VARCHAR(20) NOT NULL,         -- yfinance format: "4256.T", "3690.HK", "GOOG"; cash: "CASH.USD", "CASH.JPY"
    name VARCHAR(100),                    -- Human-readable: "CYND Co., Ltd." or "US Dollar Cash"
    currency CHAR(3) NOT NULL,            -- Original trading currency: "JPY", "HKD", "USD"
    weight_pct NUMERIC(7,6) NOT NULL,     -- Current allocation as decimal (0.084000 = 8.4%)
    cost_basis_pct NUMERIC(7,6),          -- Cost basis as fraction of portfolio value; enables % gain/loss without absolute amounts
    -- User's intrinsic value inputs (equity only, NULL for cash):
    intrinsic_value_local NUMERIC(14,4),  -- 5-year IV per share in local currency
    confidence_pct NUMERIC(5,4),          -- 0.0 to 1.0 (0% to 100%); maps to BL view uncertainty via Idzorek
    sector VARCHAR(50),                   -- For sector constraints
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Optimization run results
CREATE TABLE optimization_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id UUID NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    algorithm VARCHAR(30) NOT NULL,       -- 'BLACK_LITTERMAN', 'MEAN_VARIANCE', 'CVAR', 'HRP', 'KELLY'
    parameters JSONB NOT NULL,            -- Algorithm-specific inputs
    results JSONB NOT NULL,               -- Optimized weights, trades, metrics
    status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED',  -- 'RUNNING', 'COMPLETED', 'FAILED'
    error_message TEXT,
    computation_ms INTEGER,               -- Execution time in milliseconds
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Feature flags
CREATE TABLE feature_flags (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    flag_name VARCHAR(50) UNIQUE NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT false,
    description TEXT,
    allowed_user_ids UUID[] DEFAULT '{}',  -- Empty = applies to all (if enabled)
    rollout_pct INTEGER DEFAULT 100,       -- 0-100, for gradual rollout
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Shared: cached price history
CREATE TABLE cached_prices (
    ticker VARCHAR(20) NOT NULL,
    price_date DATE NOT NULL,
    close_price NUMERIC(14,4) NOT NULL,
    currency CHAR(3) NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (ticker, price_date)
);

-- Shared: cached FX rates (EUR-based)
CREATE TABLE cached_fx_rates (
    currency_pair VARCHAR(7) NOT NULL,    -- e.g., "EURUSD", "EURJPY"
    rate_date DATE NOT NULL,
    rate NUMERIC(14,8) NOT NULL,
    fetched_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (currency_pair, rate_date)
);

-- Shared: instrument metadata
CREATE TABLE instruments (
    ticker VARCHAR(20) PRIMARY KEY,
    name VARCHAR(200),
    exchange VARCHAR(50),
    currency CHAR(3),
    sector VARCHAR(50),
    market_cap_usd NUMERIC(16,2),         -- For BL equilibrium weights
    last_refreshed_at TIMESTAMPTZ
);

-- Indexes
CREATE INDEX idx_portfolios_user_id ON portfolios(user_id);
CREATE INDEX idx_positions_portfolio_id ON positions(portfolio_id);
CREATE INDEX idx_optimization_runs_portfolio_id ON optimization_runs(portfolio_id);
CREATE INDEX idx_cached_prices_ticker ON cached_prices(ticker);
CREATE INDEX idx_cached_fx_rates_pair ON cached_fx_rates(currency_pair);

-- Row-Level Security
ALTER TABLE portfolios ENABLE ROW LEVEL SECURITY;
ALTER TABLE positions ENABLE ROW LEVEL SECURITY;
ALTER TABLE optimization_runs ENABLE ROW LEVEL SECURITY;

CREATE POLICY portfolios_user_isolation ON portfolios
    USING (
        user_id = current_setting('app.current_user_id')::UUID
        OR current_setting('app.current_user_role') = 'ADMIN'
    );
CREATE POLICY positions_user_isolation ON positions
    USING (
        portfolio_id IN (
            SELECT id FROM portfolios WHERE user_id = current_setting('app.current_user_id')::UUID
        )
        OR current_setting('app.current_user_role') = 'ADMIN'
    );
CREATE POLICY optimization_runs_user_isolation ON optimization_runs
    USING (
        portfolio_id IN (
            SELECT id FROM portfolios WHERE user_id = current_setting('app.current_user_id')::UUID
        )
        OR current_setting('app.current_user_role') = 'ADMIN'
    );
```

### 5.3 JSONB Structure for `optimization_runs.parameters`

```json
{
  "algorithm": "BLACK_LITTERMAN",
  "risk_free_rate": 0.035,
  "tau": 0.05,
  "kelly_fraction": 0.5,
  "constraints": {
    "min_weight": 0.01,
    "max_weight": 0.08,
    "sector_max": { "Technology": 0.25 },
    "exclude_position_types": ["CASH"]
  },
  "covariance_method": "ledoit_wolf",
  "lookback_years": 3
}
```

### 5.4 JSONB Structure for `optimization_runs.results`

```json
{
  "optimized_weights": { "GOOG": 0.097, "AMZN": 0.091, "BRK.B": 0.091, ... },
  "metrics": {
    "expected_annual_return": 0.108,
    "annual_volatility": 0.165,
    "sharpe_ratio": 0.44,
    "cvar_95": -0.032,
    "effective_n_bets": 12.4,
    "max_sector_weight": { "Technology": 0.22 }
  },
  "correlation_clusters": [
    { "name": "US Tech", "tickers": ["GOOG","AMZN","NVDA","HOOD"], "avg_correlation": 0.65, "effective_n": 1.36 },
    { "name": "Insurance", "tickers": ["BRK.B","FFH"], "avg_correlation": 0.45, "effective_n": 1.38 }
  ]
}
```

**Note**: Discrete allocation (`discrete_shares`, `leftover_cash`, `trades`) is NOT stored server-side. It is computed ephemerally in the browser using the user's locally-held total portfolio value and current prices. Past runs display weights and metrics only; discrete allocation is always recomputed from current prices.
```

---

## 6. Python Optimization Microservice

### 6.1 Design Principles

- **Stateless**: No database access. No persistent state. All data arrives in the request body.
- **Pure function**: `(input_data) → (optimization_result)`
- **Fast startup**: Uvicorn with 2 workers
- **Health check**: `GET /health` returns `{"status": "ok"}`

### 6.2 API Contract

#### POST /optimize

**Request body**:

```json
{
  "algorithm": "BLACK_LITTERMAN",
  "prices": {
    "GOOG": [280.5, 282.1, ...],
    "AMZN": [195.2, 197.8, ...],
    "dates": ["2023-03-15", "2023-03-16", ...]
  },
  "market_caps": {
    "GOOG": 1850000000000,
    "AMZN": 2100000000000
  },
  "views": {
    "GOOG": 0.09,
    "AMZN": 0.166,
    "BRK.B": 0.075
  },
  "confidences": {
    "GOOG": 0.80,
    "AMZN": 0.70,
    "BRK.B": 0.90
  },
  "risk_free_rate": 0.035,
  "tau": 0.05,
  "kelly_fraction": 0.5,
  "constraints": {
    "min_weight": 0.01,
    "max_weight": 0.08,
    "long_only": true,
    "sector_constraints": { "Technology": 0.25 }
  },
  "sectors": {
    "GOOG": "Technology",
    "AMZN": "Technology",
    "BRK.B": "Financials"
  },
  "covariance_method": "ledoit_wolf"
}
```

**Response body** (200 OK):

```json
{
  "weights": { "GOOG": 0.097, "AMZN": 0.091, ... },
  "metrics": {
    "expected_annual_return": 0.108,
    "annual_volatility": 0.165,
    "sharpe_ratio": 0.44,
    "cvar_95": -0.032
  },
  "efficient_frontier": [
    { "risk": 0.10, "return": 0.065 },
    { "risk": 0.12, "return": 0.082 },
    ...
  ],
  "correlation_matrix": { ... },
  "computation_ms": 342
}
```

**Error response** (422 / 500):

```json
{
  "error": "OptimizationError",
  "message": "Covariance matrix is singular. Need more price history.",
  "detail": "..."
}
```

#### POST /analyze-correlations

Separate endpoint for computing correlation clusters without running a full optimization. Used for the "portfolio health check" feature.

#### POST /fetch-prices

Proxies yfinance price fetching for Spring Boot's market data scheduler.

**Request body**:

```json
{
  "tickers": ["GOOG", "4256.T", "CSU.TO"],
  "start_date": "2021-03-15",
  "end_date": "2026-03-15"
}
```

**Response body** (200 OK):

```json
{
  "prices": {
    "GOOG": [
      {"date": "2021-03-15", "close": 103.42},
      {"date": "2021-03-16", "close": 104.10}
    ],
    "4256.T": [
      {"date": "2021-03-15", "close": 1245.0},
      {"date": "2021-03-16", "close": 1260.0}
    ]
  },
  "metadata": {
    "GOOG": {"currency": "USD", "name": "Alphabet Inc.", "market_cap": 1850000000000, "sector": "Technology"},
    "4256.T": {"currency": "JPY", "name": "CYND Co., Ltd.", "market_cap": 52000000000, "sector": "Technology"}
  },
  "errors": {}
}
```

If a ticker fails (delisted, invalid), it appears in `errors` rather than `prices`:

```json
{
  "errors": {
    "INVALID": "No data found for ticker INVALID"
  }
}
```

#### POST /fetch-fx-rates

Proxies Frankfurter API FX rate fetching.

**Request body**:

```json
{
  "base": "EUR",
  "currencies": ["USD", "CAD", "JPY", "GBP", "MXN", "HKD"],
  "start_date": "2021-03-15",
  "end_date": "2026-03-15"
}
```

**Response body** (200 OK):

```json
{
  "rates": {
    "2021-03-15": {"USD": 1.1942, "CAD": 1.5012, "JPY": 130.21},
    "2021-03-16": {"USD": 1.1938, "CAD": 1.5008, "JPY": 130.15}
  }
}
```

#### GET /health

Returns `{"status": "ok", "version": "1.0.0"}`.

### 6.3 Implementation Notes

- All prices in the request must be in the same currency (EUR). Spring Boot handles conversion before calling.
- `prices` is a dictionary of ticker → list of daily close prices (EUR-converted), plus a `dates` key. All lists must be the same length.
- For tickers where a position has no view (no intrinsic value estimate), omit from `views` and `confidences`. BL will use equilibrium returns for those positions.
- The `efficient_frontier` field returns ~50 points along the frontier for Chart.js plotting.
- **No absolute portfolio values**: The Python service never receives `total_portfolio_value`, `latest_prices` (for discrete allocation), or share counts. Discrete allocation is handled entirely in the browser (see §8.5).

---

## 7. Spring Boot Application

### 7.1 Module Structure

```
src/main/kotlin/com/portfoliooptimizer/
├── PortfolioOptimizerApplication.kt
├── config/
│   ├── SecurityConfig.kt          -- Spring Security configuration
│   ├── WebConfig.kt               -- CORS, CSP headers, static resources
│   ├── OptimizerClientConfig.kt   -- WebClient + Resilience4j for Python service
│   └── SchedulingConfig.kt        -- @EnableScheduling for market data refresh
├── domain/
│   ├── User.kt                    -- Spring Data JDBC entity
│   ├── Portfolio.kt               -- Spring Data JDBC entity
│   ├── Position.kt                -- Spring Data JDBC entity
│   ├── OptimizationRun.kt         -- Spring Data JDBC entity, JSONB via custom Converter
│   ├── FeatureFlag.kt             -- Spring Data JDBC entity
│   ├── InviteCode.kt              -- Spring Data JDBC entity
│   ├── CachedPrice.kt             -- Spring Data JDBC entity, composite key via @Embedded
│   ├── CachedFxRate.kt            -- Spring Data JDBC entity, composite key via @Embedded
│   └── Instrument.kt              -- Spring Data JDBC entity
├── repository/
│   ├── UserRepository.kt
│   ├── PortfolioRepository.kt
│   ├── PositionRepository.kt
│   ├── OptimizationRunRepository.kt
│   ├── FeatureFlagRepository.kt
│   ├── InviteCodeRepository.kt
│   ├── CachedPriceRepository.kt
│   ├── CachedFxRateRepository.kt
│   └── InstrumentRepository.kt
├── service/
│   ├── PortfolioService.kt        -- CRUD + business logic
│   ├── OptimizerService.kt        -- Assembles percentage-based payload, calls Python, stores weights+metrics
│   ├── MarketDataService.kt       -- yfinance fetching + caching (scheduled)
│   ├── FxRateService.kt           -- Frankfurter API + caching
│   ├── CurrencyConversionService.kt -- Converts prices to EUR
│   ├── FeatureFlagService.kt      -- Check flags per user
│   ├── UserService.kt             -- User management (admin)
│   └── InviteCodeService.kt       -- Generate/validate invite codes
├── controller/
│   ├── DashboardController.kt     -- GET / (landing page after login)
│   ├── PortfolioController.kt     -- CRUD pages for portfolios/positions
│   ├── OptimizationController.kt  -- Trigger optimization, view results
│   ├── AdminController.kt         -- /admin/** user management, feature flags, system status
│   ├── AuthController.kt          -- Login, register (with invite code), logout
│   └── api/
│       ├── ChartDataController.kt -- /api/portfolios/{id}/chart-data (JSON for Chart.js)
│       ├── PriceController.kt     -- /api/prices/latest?tickers=X,Y,Z (public cached prices for browser discrete allocation)
│       └── HealthController.kt    -- /api/health
├── dto/
│   ├── OptimizerRequest.kt        -- Maps to Python service request (percentages only, no absolute values)
│   ├── OptimizerResponse.kt       -- Maps from Python service response (weights + metrics, no discrete allocation)
│   └── ChartData.kt               -- JSON DTOs for Chart.js
├── marketdata/
│   ├── YFinanceFetcher.kt         -- Calls Python service's /fetch-prices endpoint via REST
│   ├── FrankfurterClient.kt       -- REST client for api.frankfurter.dev
│   └── TickerMapper.kt            -- Maps internal tickers to yfinance format
└── security/
    ├── CustomUserDetailsService.kt -- Spring Data JDBC-backed UserDetailsService
    └── TenantFilter.kt            -- Sets app.current_user_id for RLS
```

### 7.2 Key Design Decisions

**yfinance from Kotlin**: Since yfinance is Python-only, price fetching is exposed as endpoints on the Python FastAPI service (e.g., `POST /fetch-prices`, `POST /fetch-fx-rates`). Spring Boot calls these via REST, same as optimization requests. This keeps all Python dependencies in one service and avoids `ProcessBuilder` shelling out to scripts.

**JSONB columns in Spring Data JDBC**: Register a custom `ReadingConverter` / `WritingConverter` pair that uses Jackson to serialize Kotlin data classes to/from `PGobject` with type `jsonb`. Register these converters via a `@Configuration` class extending `AbstractJdbcConfiguration`. No third-party library needed beyond Jackson (already included).

**TenantFilter**: A Spring `OncePerRequestFilter` that runs after authentication. Reads the authenticated user's ID and role, then executes `SET LOCAL app.current_user_id = '{userId}'; SET LOCAL app.current_user_role = '{role}'` on the current JDBC connection. This activates PostgreSQL RLS policies for the remainder of the request. The policies use the single-argument `current_setting()` so that an unset variable throws an error — fail-fast to surface missing TenantFilter bugs rather than silently returning empty results.

**CSP Headers (in WebConfig or SecurityConfig)**:
```
Content-Security-Policy:
  default-src 'self';
  script-src 'self';
  style-src 'self';
  img-src 'self' data:;
  connect-src 'self';
  font-src 'self';
  frame-ancestors 'none';
  form-action 'self'
```

No `unsafe-inline`, no `unsafe-eval`, no CDN origins.

---

## 8. Frontend

### 8.1 Template Engine & Architecture

**JTE 3.2.3** with Kotlin `.kte` templates in `web/src/main/jte/`. No HTMX, no Alpine.js — vanilla JavaScript only.

- **Full pages**: `page/*.kte` — use `@template.layout.base(...)` for layout wrapping
- **Partials**: `partial/*.kte` — standalone HTML snippets returned by controllers for fetch-based DOM updates (position rows, admin rows, error fragments)
- **Layout**: `layout/base.kte` — includes nav, footer, CSRF meta tags, nonce on all `<script>`/`<link>` tags
- **CSP nonces**: `CspNonceFilter` generates per-request nonces. Every script/stylesheet tag must include `nonce="${nonce}"`
- **CSRF**: `csrf-fetch.js` reads token from meta tags; all fetch POST/PUT/DELETE use `Csrf.headers()`

### 8.2 Page Structure

| Route | Template | Description |
|---|---|---|
| `GET /` | `page/index.kte` / redirect | Public: landing page. Authenticated: redirect to dashboard |
| `GET /about` | `page/about.kte` | Public information page about the service |
| `GET /login` | `page/login.kte` | Login form (public) |
| `GET /register` | `page/register.kte` | Register with invite code (public, feature-flagged) |
| `GET /dashboard` | `page/dashboard.kte` | User's portfolios list |
| `GET /portfolios/new` | `page/portfolio-form.kte` | Create new portfolio |
| `GET /portfolios/{id}` | `page/portfolio-detail.kte` | View portfolio positions, add/edit/remove |
| `GET /portfolios/{id}/optimize` | `page/optimize.kte` | Select algorithm, set constraints, run optimization |
| `GET /portfolios/{id}/results/{runId}` | `page/results.kte` | View optimization results, charts, trade list |
| `GET /admin` | `page/admin/dashboard.kte` | Admin panel: users, flags, system |
| `GET /admin/users` | `page/admin/users.kte` | User management |
| `GET /admin/flags` | `page/admin/flags.kte` | Feature flag management |

### 8.3 Fetch-Based Interaction Patterns

All dynamic interactions use vanilla JS `fetch()` + DOM manipulation (replacing former HTMX patterns):

- **Add position row**: `fetch()` GET → `tbody.insertAdjacentHTML('beforeend', html)` — handled by `portfolio-positions.js`
- **Save position**: `fetch()` POST → replace `<tr>` outerHTML — handled by `portfolio-positions.js`
- **Remove position**: `confirm()` → `fetch()` DELETE → `tr.remove()` — handled by `portfolio-positions.js`
- **Admin toggle/update**: `fetch()` POST → replace `<tr>` outerHTML — handled by `admin-users.js` / `admin-flags.js`
- **Run optimization**: `fetch()` POST form → redirect on success, inject error fragment on failure — handled by `optimize.js`

### 8.4 Chart.js via REST API Pattern

The chart canvas is rendered server-side. A separate self-hosted JS file loads data from a JSON API endpoint and initializes the chart. Chart JS files parse portfolio/run IDs from the URL path via `ChartUtils.getIdsFromUrl()`.

**Spring Boot JSON endpoint** (`ChartDataController.kt`):
```kotlin
@GetMapping("/api/portfolios/{portfolioId}/runs/{runId}/allocation-data")
fun allocationChartData(
    @PathVariable portfolioId: UUID,
    @PathVariable runId: UUID
): ResponseEntity<AllocationChartData> {
    // Fetch from optimization_runs, parse JSONB results, return chart-friendly JSON
}
```

### 8.4 Required Chart Types

1. **Allocation pie chart**: Current vs. optimized (side by side)
2. **Current vs. optimized bar chart**: Grouped bars per ticker showing weight delta
3. **Efficient frontier scatter plot**: Risk (x) vs. Return (y), with current portfolio and optimized portfolio marked as distinct points
4. **Correlation heatmap**: Matrix of pairwise correlations (Chart.js matrix plugin or custom Canvas rendering)
5. **Trade list table**: Not a chart — **browser-generated** HTML table with color-coded BUY/SELL rows (computed from discrete allocation in JS, not server-rendered)

### 8.5 Browser-Side Discrete Allocation

Discrete allocation converts continuous optimal weights into integer share counts. This runs entirely in the browser to keep absolute portfolio values private.

**Self-hosted JS file**: `/static/js/discrete-allocation.js`

**Algorithm** (greedy largest-remainder):

```javascript
/**
 * Converts percentage weights to integer share counts.
 * @param {Object} weights - {ticker: weight} from optimization result
 * @param {Object} latestPrices - {ticker: price_eur} from /api/prices/latest
 * @param {number} totalValue - user's total portfolio value (from localStorage)
 * @returns {{shares: Object, leftoverCash: number}}
 */
function discreteAllocation(weights, latestPrices, totalValue) {
    const tickers = Object.keys(weights);
    const idealShares = {};
    const floorShares = {};

    // Step 1: Compute ideal (fractional) shares and floor
    for (const t of tickers) {
        idealShares[t] = (weights[t] * totalValue) / latestPrices[t];
        floorShares[t] = Math.floor(idealShares[t]);
    }

    // Step 2: Compute remaining cash after flooring
    let spent = 0;
    for (const t of tickers) spent += floorShares[t] * latestPrices[t];
    let remaining = totalValue - spent;

    // Step 3: Greedy allocation of remaining cash
    const remainders = tickers
        .map(t => ({ ticker: t, remainder: idealShares[t] - floorShares[t] }))
        .sort((a, b) => b.remainder - a.remainder);

    for (const { ticker } of remainders) {
        if (remaining >= latestPrices[ticker]) {
            floorShares[ticker] += 1;
            remaining -= latestPrices[ticker];
        }
    }

    return { shares: floorShares, leftoverCash: remaining };
}
```

**Data flow on the results page**:

1. Server renders weights, metrics, and charts via JTE
2. Browser JS reads `totalValue` and `currentHoldings` from `localStorage`
3. Browser fetches current EUR prices from `GET /api/prices/latest?tickers=GOOG,AMZN,...` (public data)
4. Browser calls `discreteAllocation(optimizedWeights, latestPrices, totalValue)`
5. Browser computes trade list: `targetShares[t] - currentHoldings[t]` for each ticker → BUY/SELL deltas
6. Browser renders the discrete allocation table and trade list into the DOM

### 8.6 Client-Side Portfolio Entry

Users enter portfolio positions in the browser. Absolute values (shares held, cash amounts, total value) stay in `localStorage`; only percentages and position types are sent to the backend. Cost basis is sent as a percentage of portfolio value (not an absolute amount), enabling gain/loss display without revealing position sizes.

**Entry flow (equity positions)**:

1. User enters positions: ticker, shares held, cost basis (optional)
2. Browser fetches current EUR prices from `GET /api/prices/latest?tickers=...` and FX rates from cached rates
3. Browser computes `weight_pct = (shares × price_eur) / totalValue` and `cost_basis_pct = (costBasis_eur) / totalValue` for each position
4. Browser sends to backend: `{ ticker, position_type: "EQUITY", weight_pct, cost_basis_pct, intrinsic_value_local, confidence_pct, sector }`
5. Browser stores in `localStorage`: `{ ticker, shares, costBasis, currency }` per position, plus `totalValue`

**Entry flow (cash positions)**:

1. User enters cash holdings per currency (e.g., 5000 USD, 200000 JPY)
2. Browser converts each to EUR using cached FX rates
3. Browser computes `weight_pct = cash_amount_eur / totalValue` for each currency
4. Browser sends to backend: `{ ticker: "CASH.USD", position_type: "CASH", weight_pct, currency: "USD" }` (no intrinsic value, confidence, or sector)
5. Browser stores in `localStorage`: `{ amount, currency }` per cash position

**localStorage schema**:

```javascript
// Stored under key: "kernfolio_portfolio_{portfolioId}"
{
    "totalValue": 195000,
    "baseCurrency": "EUR",
    "holdings": {
        "GOOG": { "type": "EQUITY", "shares": 25, "costBasis": 6200, "currency": "USD" },
        "AMZN": { "type": "EQUITY", "shares": 40, "costBasis": 7500, "currency": "USD" },
        "CSU.TO": { "type": "EQUITY", "shares": 6, "costBasis": 18000, "currency": "CAD" }
    },
    "cash": {
        "USD": { "amount": 2100 },
        "JPY": { "amount": 350000 },
        "EUR": { "amount": 800 }
    }
}
```

**Reconciliation**: When the browser has both `totalValue` and per-position share counts, it can recompute live weights from `shares × current_price_eur / totalValue` and compare against the `weight_pct` stored in the backend. If drift exceeds a threshold (e.g., price movements since last update), the UI flags stale weights and offers to resubmit updated percentages. Cash positions are revalued continuously against the base currency using cached FX rates — a JPY cash position's weight shifts as EUR/JPY moves.

**Deriving share counts from totalValue**: If `localStorage` is cleared (new device, cleared cache), the browser can approximate share counts from the backend's `weight_pct` and current prices: `shares ≈ round(totalValue × weight_pct / price_eur)`. The user confirms or corrects these, then localStorage is repopulated. The backend still has the percentage weights, so optimization history is not lost.

---

## 9. Market Data Pipeline

### 9.1 Price Data Flow

1. **Scheduled job** runs daily at 22:00 UTC (after all target exchanges close)
2. Job reads all unique tickers from `positions` table across all users
3. For each ticker, fetch daily close prices for the last 5 years from yfinance (or update from last cached date)
4. Store in `cached_prices` table
5. Also refresh `instruments` table (market cap, sector, name) for BL equilibrium weights

### 9.2 FX Rate Data Flow

1. Same scheduled job fetches EUR-based FX rates from Frankfurter API
2. Required pairs: EURUSD, EURCAD, EURJPY, EURGBP, EURMXN, EURHKD (plus any others from user positions)
3. Store in `cached_fx_rates` table
4. Historical rates needed for converting price series to EUR for covariance computation

### 9.3 yfinance Integration

Since yfinance is Python-only, the recommended approach is to add a `/fetch-prices` endpoint to the Python FastAPI service:

```python
@app.post("/fetch-prices")
async def fetch_prices(request: PriceFetchRequest):
    """Fetch historical prices from yfinance. Called by Spring Boot scheduler."""
    import yfinance as yf
    data = yf.download(
        tickers=request.tickers,
        start=request.start_date,
        end=request.end_date,
        group_by='ticker',
        auto_adjust=True
    )
    # Return as JSON: {ticker: [{date, close}, ...]}
```

### 9.4 Ticker Format Mapping

| Internal / Display | yfinance Format | Exchange |
|---|---|---|
| GOOG | GOOG | NASDAQ |
| AMZN | AMZN | NASDAQ |
| CSU.TO | CSU.TO | TSX |
| 4256.T / CYND | 4256.T | Tokyo |
| 4975.T / JCU | 4975.T | Tokyo |
| 3690.HK / Meituan | 3690.HK | HKEX |
| 9880.HK / UBTECH | 9880.HK | HKEX |
| GMEXICOB | GMEXICOB.MX | BMV Mexico |
| TEP.PA | TEP.PA | Euronext Paris |
| ODET | ODET.PA | Euronext Paris |
| FAST (Fastned) | FAST.AS | Euronext Amsterdam |
| PRE (Pensana) | PRE.L | LSE London |
| H4N (Solar Foods) | H4N.HE | Helsinki |
| HY9H (SK Hynix GDR) | HY9H.DE | Frankfurt/Xetra (GDR) |
| FFH | FFH.TO | TSX |
| ANIC | ANIC.L | LSE London |
| 8PSB | 8PSB.L | LSE London (ETC) |
| EGLN | EGLN.L | LSE London (ETC) |
| DFND | DFND.L | LSE London (ETF) |

Note: Some ETFs/ETCs may need different suffixes. The `TickerMapper` service maintains this mapping and should be easily editable.

### 9.5 Caching Strategy

- Prices: Store 5 years of daily closes. On refresh, only fetch from `MAX(price_date) + 1 day` to today.
- FX rates: Store 5 years. Same incremental update.
- Market caps: Refresh weekly (less volatile, saves API calls).
- If yfinance is down: Serve from cache. A 1-day-old close price is perfectly valid for portfolio optimization.
- Cache invalidation: Manual "refresh now" button in admin panel for troubleshooting.

---

## 10. Authentication & Authorization

### 10.1 Approach: Spring Security with Session-Based Form Login

- **Session-based, NOT JWT**: HTTP-only session cookies. Cannot be exfiltrated by XSS. Instant invalidation on logout.
- **Two roles**: `ROLE_USER` and `ROLE_ADMIN`
- **Password storage**: bcrypt via Spring Security's `BCryptPasswordEncoder`
- **Session timeout**: 30 minutes of inactivity
- **CSRF protection**: Enabled (Spring Security default). Forms include hidden `_csrf` field. For fetch AJAX requests, `csrf-fetch.js` reads token from `<meta>` tags and includes it via `Csrf.headers()`.

### 10.2 Route Protection

```kotlin
@Bean
fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
    .authorizeHttpRequests { auth ->
        auth.requestMatchers("/", "/login", "/register", "/about", "/static/**").permitAll()  // Public: landing, login, registration, about, static assets
        auth.requestMatchers("/admin/**").hasRole("ADMIN")
        auth.requestMatchers("/api/**").authenticated()  // All JSON endpoints require session auth (including /api/prices/latest)
        auth.anyRequest().authenticated()
    }
    .formLogin { form ->
        form.loginPage("/login").defaultSuccessUrl("/", true)
    }
    .logout { logout ->
        logout.logoutSuccessUrl("/login?logout")
    }
    .csrf { csrf ->
        // CSRF enabled by default. Configure for HTMX compatibility.
    }
    .headers { headers ->
        headers.contentSecurityPolicy { csp ->
            csp.policyDirectives("default-src 'self'; script-src 'self'; style-src 'self'; ...")
        }
    }
    .build()
```

### 10.3 Fetch + CSRF Integration

Vanilla JS `fetch()` requests need the CSRF token. Pattern:

**In JTE layout `<head>`** (every page via `layout/base.kte`):
```html
<meta name="csrf-token" content="${csrfToken}">
<meta name="csrf-header" content="${csrfHeaderName}">
```

**In `/static/js/csrf-fetch.js`** (loaded on every page):
```javascript
var Csrf = (function () {
    var token = document.querySelector('meta[name="csrf-token"]')?.content;
    var header = document.querySelector('meta[name="csrf-header"]')?.content;
    function headers(extra) {
        var h = {};
        if (token && header) h[header] = token;
        if (extra) { for (var k in extra) h[k] = extra[k]; }
        return h;
    }
    return { headers: headers, token: token, header: header };
})();
```

Usage: `fetch(url, { method: 'POST', headers: Csrf.headers(), body: formData })`

### 10.4 Admin Bootstrapping

On first startup, if the `users` table is empty:

1. App reads `ADMIN_EMAIL` from Vault KV store
2. Generates an invite code and sends it to `ADMIN_EMAIL` via SMTP (credentials from Vault KV)
3. Admin clicks the invite link, sets username and password
4. Account is created with role `ADMIN`

No special bootstrap endpoint or console token — the admin is just the first invited user. If `ADMIN_EMAIL` is unset or `users` is non-empty, this step is skipped.

**Dev/local fallback**: If SMTP is not configured (no `spring.mail.host` in Vault or properties), the invite code is logged to stdout at `WARN` level: `"ADMIN INVITE CODE (no SMTP configured): {code}"`. This allows bootstrapping in local dev without an SMTP server.

### 10.5 Invite-Only Registration

1. Admin creates invite code via admin panel → stored in `invite_codes` table
2. System emails the invite link to the intended user
3. User clicks the link, sets username and password
4. System validates code (exists, unused, not expired), creates user, marks code as used
5. Later (feature flag `SELF_REGISTRATION`): Skip invite code requirement

---

## 11. Feature Flags

### 11.1 Phase Mapping

| Flag Name | Default | Phase | Controls |
|---|---|---|---|
| `ALGO_BLACK_LITTERMAN` | ON | 1 | BL optimization available |
| `ALGO_MEAN_VARIANCE` | ON | 1 | Markowitz efficient frontier |
| `ALGO_KELLY` | OFF | 2 | Multi-asset Kelly criterion |
| `ALGO_CVAR` | OFF | 3 | CVaR optimization |
| `ALGO_HRP` | OFF | 3 | Hierarchical Risk Parity |
| `SHOW_EFFICIENT_FRONTIER` | ON | 1 | Efficient frontier chart |
| `SHOW_CORRELATION_HEATMAP` | OFF | 2 | Correlation matrix visualization |
| `SHOW_CLUSTER_ANALYSIS` | OFF | 3 | Correlation cluster breakdown |
| `SELF_REGISTRATION` | OFF | Later | Public registration (no invite code) |
| `IMPORT_IBKR` | OFF | Later | Import portfolio from IBKR CSV/report |

### 11.2 Usage in Kotlin

```kotlin
@Service
class FeatureFlagService(private val repo: FeatureFlagRepository) {
    fun isEnabled(flagName: String, userId: UUID): Boolean {
        val flag = repo.findByFlagName(flagName) ?: return false
        if (!flag.enabled) return false
        if (flag.allowedUserIds.isNotEmpty() && userId !in flag.allowedUserIds) return false
        if (flag.rolloutPct < 100) {
            return (userId.hashCode().absoluteValue % 100) < flag.rolloutPct
        }
        return true
    }
}
```

### 11.3 Usage in Templates

Expose via `@ControllerAdvice` model attribute (`FeatureFlagAdvice`):

```html
<div th:if="${featureFlags.isEnabled('ALGO_CVAR')}">
    <option value="CVAR">CVaR (Expected Shortfall)</option>
</div>
```

---

## 12. Deployment

### 12.1 Secrets Management: HashiCorp Vault

All secrets (database credentials, SMTP credentials, session signing keys) are managed by HashiCorp Vault. No secrets are stored in `.env` files, docker-compose environment blocks, or source code.

**Vault services used**:

| Secrets Engine | Purpose | Consumers |
|---|---|---|
| **Database** (PostgreSQL) | Dynamic, short-lived DB credentials with auto-rotation | Spring Boot (`app`), pg_dump backup script |
| **KV v2** | Static secrets: SMTP host/user/password, `ADMIN_EMAIL`, session signing key | Spring Boot (`app`) |
| **PKI** | mTLS certificates for inter-service communication | All services (app ↔ optimizer, app ↔ postgres) |

**Integration approach**:

- **Spring Boot**: `spring-cloud-starter-vault-config` reads secrets at startup and refreshes on lease renewal. DB credentials come from Vault's database engine (dynamic). SMTP and other static secrets come from KV v2.
- **Python FastAPI**: `hvac` library authenticates to Vault via AppRole on startup. For the stateless optimizer, the only secret needed is its own mTLS client certificate (injected by Vault Agent sidecar).
- **Vault Agent sidecar**: Runs alongside each service container. Manages token renewal, certificate rotation (PKI), and writes secrets to a shared tmpfs volume. Services read certificates from the tmpfs mount — no secrets touch disk.
- **PostgreSQL**: Initial bootstrap credentials for Vault's database engine setup are passed via Docker secrets (one-time setup). After that, Vault manages all DB credentials dynamically.

**mTLS between containers**:

All inter-service communication uses mutual TLS. Vault's PKI engine issues short-lived certificates (TTL: 24h, auto-renewed by Vault Agent).

- `app` ↔ `optimizer`: mTLS on port 8000. Spring Boot's `WebClient` configured with Vault-issued client cert. FastAPI's Uvicorn configured with Vault-issued server cert + client CA verification.
- `app` ↔ `postgres`: mTLS via PostgreSQL's `sslmode=verify-full`. Vault-issued client certs replace password-only auth.
- `caddy` ↔ `app`: mTLS on port 8080. Caddy uses Vault-issued client cert for upstream connections.

**Dev/local mode**: For local development and digital twins, Vault runs in `-dev` mode (in-memory, unsealed, root token = `dev-root-token`). The `docker-compose.dev.yml` override starts Vault in dev mode and pre-seeds it with test secrets via a `vault-init` container.

### 12.2 Docker Compose

```yaml
services:
  vault:
    image: hashicorp/vault:1.19
    cap_add:
      - IPC_LOCK
    environment:
      VAULT_ADDR: "http://0.0.0.0:8200"
    volumes:
      - vault_data:/vault/data
      - ./vault/config:/vault/config:ro
    command: vault server -config=/vault/config/vault.hcl
    healthcheck:
      test: ["CMD", "vault", "status"]
      interval: 10s
      timeout: 5s
      retries: 5

  vault-init:
    image: hashicorp/vault:1.19
    depends_on:
      vault:
        condition: service_healthy
    volumes:
      - ./vault/init:/vault/init:ro
      - vault_agent_certs:/vault/certs
    entrypoint: /vault/init/setup.sh
    environment:
      VAULT_ADDR: "http://vault:8200"

  postgres:
    image: postgres:18-alpine
    environment:
      POSTGRES_DB: portfolio_optimizer
    volumes:
      - pgdata:/var/lib/postgresql/data
      - vault_agent_certs:/vault/certs:ro
      - ./postgres/pg_hba_mtls.conf:/etc/postgresql/pg_hba.conf:ro
      - ./postgres/postgresql_ssl.conf:/etc/postgresql/conf.d/ssl.conf:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready"]
      interval: 10s
      timeout: 5s
      retries: 5
    depends_on:
      vault-init:
        condition: service_completed_successfully

  optimizer:
    build: ./optimizer
    environment:
      VAULT_ADDR: "http://vault:8200"
      PYTHONUNBUFFERED: "1"
    volumes:
      - vault_agent_certs:/vault/certs:ro
    healthcheck:
      test: ["CMD", "curl", "--cacert", "/vault/certs/ca.pem", "--cert", "/vault/certs/optimizer-client.pem", "--key", "/vault/certs/optimizer-client-key.pem", "-f", "https://localhost:8000/health"]
      interval: 30s
      timeout: 10s
      retries: 3
    depends_on:
      vault-init:
        condition: service_completed_successfully

  app:
    build: ./web
    environment:
      VAULT_ADDR: "http://vault:8200"
      SPRING_PROFILES_ACTIVE: prod
      SPRING_CLOUD_VAULT_TOKEN: "${VAULT_APP_TOKEN}"
      OPTIMIZER_BASE_URL: "https://optimizer:8000"
    volumes:
      - vault_agent_certs:/vault/certs:ro
    depends_on:
      postgres:
        condition: service_healthy
      optimizer:
        condition: service_healthy
      vault-init:
        condition: service_completed_successfully

  caddy:
    image: caddy:2-alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile:ro
      - caddy_data:/data
      - caddy_config:/config
      - vault_agent_certs:/vault/certs:ro
    depends_on:
      - app

volumes:
  pgdata:
  caddy_data:
  caddy_config:
  vault_data:
  vault_agent_certs:
```

### 12.3 Caddyfile

```
portfolio.yourdomain.com {
    reverse_proxy https://app:8080 {
        transport http {
            tls_client_auth /vault/certs/caddy-client.pem /vault/certs/caddy-client-key.pem
            tls_trusted_ca_certs /vault/certs/ca.pem
        }
    }
}
```

### 12.4 Backup Cron

Backup uses Vault-issued short-lived DB credentials:

```bash
# /etc/cron.d/portfolio-backup
# Fetches a one-time Vault DB credential, dumps, and the credential auto-expires
0 3 * * * root /opt/kernfolio/scripts/vault-pg-dump.sh | gzip > /backups/portfolio-$(date +\%Y\%m\%d).sql.gz
0 4 * * * root find /backups -name "portfolio-*.sql.gz" -mtime +7 -delete
```

---

## 13. Security Requirements

### 13.1 Mandatory

- [ ] **No inline scripts or styles** — all JS in self-hosted `.js` files, all CSS in self-hosted `.css` files
- [ ] **Strict CSP header**: `script-src 'self'; style-src 'self'` — no `unsafe-inline`, no `unsafe-eval`
- [ ] **Self-hosted JS libraries**: Chart.js via WebJar
- [ ] **HTTP-only, Secure, SameSite=Lax session cookies**
- [ ] **CSRF protection** on all state-changing requests (forms and fetch AJAX)
- [ ] **bcrypt password hashing** (cost factor 10+)
- [ ] **RLS on PostgreSQL** for defense-in-depth tenant isolation
- [ ] **HTTPS only** (Caddy auto-TLS)
- [ ] **No secrets in source code** — all secrets managed by HashiCorp Vault (see §12.1). No `.env` files with credentials.
- [ ] **mTLS between all containers** — Vault PKI-issued certificates for app ↔ optimizer, app ↔ postgres, caddy ↔ app. No plaintext inter-service communication.
- [ ] **Dynamic database credentials** — Vault database secrets engine issues short-lived PostgreSQL credentials. No static DB passwords.
- [ ] **Python service not exposed externally** — only reachable via Docker internal network, mTLS-authenticated
- [ ] **Input validation** on all user inputs (Kotlin: Jakarta Bean Validation; Python: Pydantic)
- [ ] **Rate limiting** on login attempts (Spring Security's `AuthenticationFailureHandler` with exponential backoff or lockout)
- [ ] **Client-side portfolio values**: Absolute portfolio values (share counts, cash amounts, total portfolio value) are stored exclusively in the browser's `localStorage`. The backend never receives or stores these values. Only percentage weights and position types (`EQUITY`/`CASH`) are transmitted and persisted server-side. Cash positions use synthetic tickers (e.g., `CASH.USD`) and are continuously revalued against the base currency via FX rates
- [ ] **Authenticated price endpoint**: `GET /api/prices/latest` returns cached market prices. Requires session authentication (no unauthenticated access) to prevent external abuse and search engine indexing. Not wealth-revealing — returns only public market prices.

### 13.2 Recommended

- [ ] Security headers: X-Content-Type-Options, X-Frame-Options, Referrer-Policy, Permissions-Policy
- [ ] Dependency vulnerability scanning (Gradle: OWASP Dependency-Check; Python: pip-audit)
- [ ] Audit log for admin actions (user creation, feature flag changes)
- [ ] Note: `localStorage` is not encrypted — if the user's device is compromised, portfolio values are visible. This is an acceptable tradeoff for a self-hosted app with strict CSP (no XSS vector to exfiltrate localStorage)

---

## 14. Phase 1 Implementation Blocks

**Goal**: A working portfolio optimizer with Black-Litterman and Markowitz, built leaf-first in independently-testable blocks.

### 14.1 Design Decisions

1. **Vault is Block 8 (last).** Every other block uses plain env-var secrets. Vault is layered in as a cross-cutting concern after all features work. This avoids Vault infrastructure blocking any feature work.
2. **Digital twins and Python fetch endpoints are separate blocks.** Twins are pure leaves serving fixture data; Python endpoints call real external APIs. Both implement the same API contract. In dev mode, `docker-compose.dev.yml` routes `YFINANCE_BASE_URL` / `FRANKFURTER_BASE_URL` to the twins, bypassing real APIs entirely.
3. **Test fixtures are Block 0.** The 37-position portfolio JSON, fixture price CSVs, fixture FX rates, and golden-file optimization outputs are created first so every subsequent block has test data.

### 14.2 Dependency Graph

```
Wave 1 (parallel, no deps):
  Block 0:  Test Data Fixtures
  Block 2c: Tailwind CSS + Layout + Vendor JS

Wave 2 (depend on Block 0, parallel):
  Block 1a: Frankfurter Digital Twin
  Block 1b: yFinance Digital Twin
  Block 1c: Python /fetch-prices + /fetch-fx-rates
  Block 1d: Python /optimize (BL + MVO)
  Block 2a: Liquibase Schema Migration

Wave 3 (depends on 2a):
  Block 2b: Entities + Repositories (Spring Data JDBC)

Wave 4 (depend on 2b, parallel):
  Block 3a: Spring Security + TenantFilter
  Block 3b: FeatureFlagService + Seeded Flags
  Block 3c: Market Data Services

Wave 5 (depend on Wave 4 + 2c, parallel):
  Block 4a: Auth Pages (login, register, invite)
  Block 4b: Admin Panel
  Block 4c: Portfolio CRUD

Wave 6:
  Block 5a: OptimizerService (orchestration)

Wave 7:
  Block 5b: Results Page (server-rendered + charts)

Wave 8 (parallel):
  Block 6a: Browser Discrete Allocation JS
  Block 6b: Client-Side Portfolio Entry (localStorage)
  Block 7a: Public Pages (landing, about)

Wave 9:
  Block 8:  Vault Integration (cross-cutting)
```

### 14.3 Block Details

Each block specifies: scope, directories touched, dependencies (blocks that must be completed first), and verification method.

---

#### Block 0 — Test Data Fixtures

**Scope**: 37-position portfolio JSON from Appendix B. Fixture price CSVs (3 years of daily closes for all tickers in the portfolio). Fixture FX rate JSON (EUR-based rates for USD, CAD, JPY, GBP, MXN, HKD). Pre-computed optimization golden files. WireMock response stubs for the Python service.

**Directories**:
- `optimizer/tests/fixtures/` — portfolio JSON, price CSV, expected optimization outputs
- `web/src/test/resources/fixtures/` — same portfolio JSON, WireMock stubs, price/FX fixtures

**Dependencies**: None (true leaf).

**Verification**: Files parse correctly. JUnit test deserializes fixture JSON. `python -c "import json; json.load(open('tests/fixtures/test-portfolio.json'))"`.

---

#### Block 1a — Frankfurter Digital Twin

**Scope**: Spring Boot app on port 8082 serving fixture FX rates matching the Frankfurter API contract (`GET /v1/{start_date}..{end_date}?from=EUR&to=USD,CAD,...`). Returns deterministic data from Block 0 fixtures. No external API calls.

**Directories**: `digital-twins/frankfurter-fake/src/`

**Dependencies**: Block 0.

**Verification**: `bootRun` + `curl http://localhost:8082/v1/2023-01-01..2023-01-05?from=EUR&to=USD` returns expected JSON. `@SpringBootTest` integration test.

---

#### Block 1b — yFinance Digital Twin

**Scope**: Spring Boot app on port 8081 serving fixture prices matching the Python `/fetch-prices` response contract. Serves instrument metadata. Deterministic, no external calls.

**Directories**: `digital-twins/yfinance-fake/src/`

**Dependencies**: Block 0.

**Verification**: `bootRun` + `curl -X POST http://localhost:8081/fetch-prices -d '{"tickers":["GOOG"],...}'` returns expected JSON. `@SpringBootTest` integration test.

---

#### Block 1c — Python Fetch Endpoints

**Scope**: Two FastAPI endpoints: `POST /fetch-prices` (yfinance wrapper) and `POST /fetch-fx-rates` (Frankfurter HTTP client). Pydantic request/response schemas per §6.2. Add `yfinance` + `httpx` to `pyproject.toml`.

**Directories**: `optimizer/app/` (schemas.py, fetchers.py, main.py), `optimizer/tests/`

**Dependencies**: Block 0. Independent of Blocks 1a/1b.

**Verification**: `pytest tests/test_fetch_prices.py tests/test_fetch_fx_rates.py` with mocked yfinance/httpx.

---

#### Block 1d — Python /optimize (BL + MVO)

**Scope**: `POST /optimize` endpoint implementing Black-Litterman with Idzorek confidence and Mean-Variance (max Sharpe). Pydantic models for `OptimizeRequest` / `OptimizeResponse` per §6.2. Uses `pypfopt.BlackLittermanModel`, `pypfopt.EfficientFrontier`, `pypfopt.risk_models.CovarianceShrinkage` (Ledoit-Wolf).

**Directories**: `optimizer/app/` (optimizer.py, schemas.py, main.py), `optimizer/tests/`

**Dependencies**: Block 0 (golden files for regression tests).

**Verification**: `pytest tests/test_optimize.py` — weights within ±0.5% of golden files. Manual: POST fixture data to `http://localhost:8000/optimize`.

---

#### Block 2a — Liquibase Schema Migration

**Scope**: `001-initial-schema.yaml` changeset: all tables from §5.2 (`users`, `invite_codes`, `portfolios`, `positions`, `optimization_runs`, `feature_flags`, `cached_prices`, `cached_fx_rates`, `instruments`), indexes, `pgcrypto` extension, RLS policies, `COMMENT ON` statements per §5.1.

**Directories**: `web/src/main/resources/db/changelog/`

**Dependencies**: None (pure leaf).

**Verification**: Start PostgreSQL, `bootRun`, Liquibase applies migration. `psql`: `\dt` lists all tables, `\d+ positions` shows columns with comments, RLS enabled on portfolios/positions/optimization_runs.

---

#### Block 2b — Entities + Repositories (Spring Data JDBC)

**Scope**: 9 Kotlin data classes as Spring Data JDBC entities (`User`, `InviteCode`, `Portfolio`, `Position`, `OptimizationRun`, `FeatureFlag`, `CachedPrice`, `CachedFxRate`, `Instrument`) and 9 `CrudRepository` interfaces. Composite keys for `CachedPrice` and `CachedFxRate` via `@Embedded`. JSONB columns via custom `ReadingConverter`/`WritingConverter` with Jackson. UUID array for `FeatureFlag.allowedUserIds`. Custom `@Query` with native SQL where needed. `AbstractJdbcConfiguration` subclass to register converters. Add Testcontainers to `build.gradle.kts`.

**Directories**: `web/src/main/kotlin/com/kernfolio/domain/`, `web/src/main/kotlin/com/kernfolio/repository/`, `web/src/main/kotlin/com/kernfolio/config/JdbcConfig.kt`, `web/src/test/`, `web/build.gradle.kts`

**Dependencies**: Block 2a.

**Verification**: Testcontainers integration tests (insert/query/delete against real PostgreSQL with Liquibase migrations applied). Schema matches entities (no ORM validation — Liquibase is the source of truth).

---

#### Block 2c — Tailwind CSS + Layout + Vendor JS

**Scope**: Tailwind standalone CLI Gradle task (§4.3). `input.css` with `@import "tailwindcss"`. Base JTE layout template (`layout/base.kte`) with nonce-based CSP, CSRF meta tags. Chart.js 4.5.1 via WebJar. `csrf-fetch.js` for CSRF in fetch requests (§10.3).

**Directories**: `web/build.gradle.kts`, `web/tools/`, `web/src/main/resources/static/`, `web/src/main/resources/templates/layout/`

**Dependencies**: None (pure infrastructure).

**Verification**: `gradlew tailwindBuild` produces CSS. `bootRun` renders layout template. No CSP violations in browser dev tools.

---

#### Block 3a — Spring Security + TenantFilter

**Scope**: `SecurityConfig.kt` with route protection per §10.2 (`/`, `/login`, `/register`, `/about`, `/static/**` public; `/admin/**` requires ADMIN; all else authenticated). `CustomUserDetailsService.kt` backed by `UserRepository`. `TenantFilter.kt` — `OncePerRequestFilter` that runs `SET LOCAL app.current_user_id` / `app.current_user_role` for PostgreSQL RLS. `BCryptPasswordEncoder` bean. Session timeout (30 min). CSP headers.

**Directories**: `web/src/main/kotlin/com/kernfolio/config/`, `web/src/main/kotlin/com/kernfolio/security/`

**Dependencies**: Block 2b.

**Verification**: Tests verify public/protected/admin route access rules. TenantFilter test verifies `SET LOCAL` is executed with correct user ID.

---

#### Block 3b — FeatureFlagService + Seeded Flags

**Scope**: `FeatureFlagService.kt` with `isEnabled(flagName, userId)` per §11.2. `@ControllerAdvice` exposing flags to JTE templates. `002-seed-feature-flags.yaml` changeset inserting Phase 1 flags from §11.1 (ALGO_BLACK_LITTERMAN=ON, ALGO_MEAN_VARIANCE=ON, SHOW_EFFICIENT_FRONTIER=ON, all others OFF).

**Directories**: `web/src/main/kotlin/com/kernfolio/service/`, `web/src/main/kotlin/com/kernfolio/config/`, `web/src/main/resources/db/changelog/`

**Dependencies**: Block 2b.

**Verification**: MockK unit tests for flag logic (enable/disable, rollout %, per-user allow-list). Integration test verifies seeded flags exist after migration.

---

#### Block 3c — Market Data Services

**Scope**: `MarketDataService.kt` (calls Python `/fetch-prices`, stores in `cached_prices` + `instruments`). `FxRateService.kt` (calls Frankfurter API or Python `/fetch-fx-rates`, stores in `cached_fx_rates`). `CurrencyConversionService.kt` (converts price series to EUR using cached FX rates). `OptimizerClientConfig.kt` (WebClient bean). `SchedulingConfig.kt` (`@EnableScheduling`). `YFinanceFetcher.kt`, `FrankfurterClient.kt`, `TickerMapper.kt`. `PriceController.kt` (`GET /api/prices/latest?tickers=X,Y,Z` — authenticated, for browser discrete allocation).

**Directories**: `web/src/main/kotlin/com/kernfolio/service/`, `web/src/main/kotlin/com/kernfolio/marketdata/`, `web/src/main/kotlin/com/kernfolio/controller/api/`, `web/src/main/kotlin/com/kernfolio/config/`

**Dependencies**: Block 2b. Contract from Block 1c/1a/1b (tests use WireMock).

**Verification**: WireMock-based tests for price fetching, FX rate fetching, EUR conversion, caching, incremental updates. Manual with digital twins: `bootRun` with dev profile, verify `/api/prices/latest?tickers=GOOG` returns cached prices.

---

#### Block 4a — Auth Pages (Login, Register, Invite Flow)

**Scope**: `AuthController.kt` with login page, registration with invite code validation. JTE templates: `page/login.kte`, `page/register.kte`. `UserService.kt` (user creation with bcrypt). `InviteCodeService.kt` (generation, validation). Admin bootstrap logic (first-run: log invite code to stdout if no SMTP, per §10.4).

**Directories**: `web/src/main/kotlin/com/kernfolio/controller/`, `web/src/main/kotlin/com/kernfolio/service/`, `web/src/main/resources/templates/`

**Dependencies**: Block 3a, Block 2c, Block 2b.

**Verification**: `@WebMvcTest` for login/register pages. CSRF token present. Valid/invalid invite code handling.

---

#### Block 4b — Admin Panel

**Scope**: `AdminController.kt` — user management (list, create invite codes, enable/disable), feature flag management (list, toggle, edit rollout %). JTE templates: `page/admin/dashboard.kte`, `page/admin/users.kte`, `page/admin/flags.kte`. Vanilla JS fetch for inline flag editing and user toggle.

**Directories**: `web/src/main/kotlin/com/kernfolio/controller/`, `web/src/main/resources/templates/admin/`

**Dependencies**: Block 3a, Block 3b, Block 4a, Block 2c.

**Verification**: `@WebMvcTest` verifies admin routes require ADMIN role. Flag toggle and invite code creation work.

---

#### Block 4c — Portfolio CRUD

**Scope**: `PortfolioService.kt` (CRUD for portfolios and positions). `DashboardController.kt` (portfolio list). `PortfolioController.kt` (detail view, add/edit/remove positions via fetch). Templates: `page/dashboard.kte`, `page/portfolio-form.kte`, `page/portfolio-detail.kte`. Backend receives only percentage weights — no absolute values.

**Directories**: `web/src/main/kotlin/com/kernfolio/service/`, `web/src/main/kotlin/com/kernfolio/controller/`, `web/src/main/resources/templates/`

**Dependencies**: Block 3a, Block 2b, Block 2c, Block 3b.

**Verification**: `@WebMvcTest` for portfolio CRUD and partial template responses. RLS integration test: user A cannot see user B's portfolios.

---

#### Block 5a — OptimizerService (Orchestration)

**Scope**: `OptimizerService.kt` — full optimization flow: (1) load portfolio positions from DB, (2) load cached prices + FX rates, (3) convert intrinsic values to CAGR-based Q vector (`Q_k = (IV_k / P_k)^(1/5) − 1`), (4) assemble `OptimizeRequest` DTO (EUR-converted, percentage weights only), (5) call Python `/optimize` via WebClient, (6) store results in `optimization_runs`. DTOs: `OptimizerRequest.kt`, `OptimizerResponse.kt`. `OptimizationController.kt` with algorithm selection form. Template: `optimize.html`.

**Directories**: `web/src/main/kotlin/com/kernfolio/service/`, `web/src/main/kotlin/com/kernfolio/dto/`, `web/src/main/kotlin/com/kernfolio/controller/`, `web/src/main/resources/templates/`

**Dependencies**: Block 3c, Block 4c, Block 1d (WireMock in tests), Block 2c.

**Verification**: Unit tests for IV→CAGR conversion, payload assembly (correct tickers, EUR prices, views only for positions with intrinsic values). WireMock integration test for full round-trip.

---

#### Block 5b — Results Page (Server-Rendered + Charts)

**Scope**: `results.html` template showing optimized weights, metrics (expected return, volatility, Sharpe). `ChartDataController.kt` JSON endpoints for Chart.js. Chart JS files: `allocation-pie.js`, `allocation-bar.js`, `efficient-frontier.js` under `/static/js/charts/`. `ChartData.kt` DTOs.

**Directories**: `web/src/main/kotlin/com/kernfolio/controller/api/`, `web/src/main/kotlin/com/kernfolio/dto/`, `web/src/main/resources/templates/`, `web/src/main/resources/static/js/charts/`

**Dependencies**: Block 5a, Block 2c.

**Verification**: `@WebMvcTest` for chart endpoints. Manual: run optimization, view results page, charts render. No CSP violations.

---

#### Block 6a — Browser Discrete Allocation JS

**Scope**: `/static/js/discrete-allocation.js` implementing greedy largest-remainder algorithm from §8.5. Reads `totalValue` from localStorage, fetches latest prices from `/api/prices/latest`, computes integer share counts, renders trade list (color-coded BUY/SELL) into the results page DOM.

**Directories**: `web/src/main/resources/static/js/`, modify `results.html`

**Dependencies**: Block 5b, Block 3c (`/api/prices/latest`).

**Verification**: Manual: set localStorage value, view results page, see discrete allocation table and trade list.

---

#### Block 6b — Client-Side Portfolio Entry (localStorage)

**Scope**: `/static/js/portfolio-entry.js` (vanilla JS). Handles client-side portfolio entry flow from §8.6: user enters share counts / cash amounts → JS computes `weight_pct` → sends only percentages to backend → stores shares, cost basis, cash in localStorage. Reconciliation logic to detect stale weights (price drift). localStorage schema per §8.6.

**Directories**: `web/src/main/resources/static/js/`, modify `portfolio-detail.html`

**Dependencies**: Block 4c, Block 3c, Block 2c.

**Verification**: Manual: enter share counts → verify localStorage populated → verify only `weight_pct` sent to backend (network tab) → verify reconciliation warning on price drift.

---

#### Block 7a — Public Pages (Landing, About)

**Scope**: `landing.html` (shown for unauthenticated users at `/`, with login + about links). `about.html` (service description). Modify `DashboardController.kt` to branch: unauthenticated → landing, authenticated → dashboard.

**Directories**: `web/src/main/resources/templates/`, modify `DashboardController.kt`

**Dependencies**: Block 2c. Touches Block 4c's `DashboardController`.

**Verification**: Visit `/` without login → landing. Visit `/about` → info page. No auth required.

---

#### Block 8 — Vault Integration (Cross-Cutting)

**Scope**: HashiCorp Vault in dev mode for local development. Vault database engine for dynamic PostgreSQL credentials. KV v2 for SMTP, session signing key, ADMIN_EMAIL. PKI engine for mTLS certificates between all containers. `spring-cloud-starter-vault-config` in Spring Boot. `hvac` in Python service. Vault Agent sidecar config. Updated `docker-compose.yml` / `docker-compose.dev.yml` per §12.

**Directories**: `vault/config/`, `vault/init/`, `docker-compose.yml`, `docker-compose.dev.yml`, `web/build.gradle.kts`, `web/src/main/resources/application.yml`, `optimizer/pyproject.toml`, `optimizer/app/main.py`

**Dependencies**: All previous blocks.

**Verification**: `docker compose up` — all services start. Vault initializes, web app gets dynamic DB creds, mTLS works between services. `vault kv get secret/kernfolio/smtp` returns test config.

---

## 15. Future Phases

### Phase 2: Enhanced Analytics

1. Multi-asset Kelly criterion optimization
2. Efficient frontier chart (Chart.js scatter plot)
3. Correlation heatmap
4. Current vs. optimized comparison (bar chart + side-by-side table)
5. Portfolio "health check" (effective N-bets per cluster)
6. Multiple candidate portfolios per user (compare side-by-side)

### Phase 3: Advanced Optimization

1. CVaR optimization (Riskfolio-Lib integration)
2. Hierarchical Risk Parity (robustness check)
3. Risk budgeting
4. Sector constraints UI
5. Stress testing (multiplied correlations)

### Phase 4: Polish & Growth

1. Self-registration (feature-flagged)
2. IBKR CSV/report import
3. PDF export of optimization report
4. Email notifications (portfolio drift alerts)
5. Mobile-responsive UI improvements

---

## 16. Testing Strategy

### 16.1 Spring Boot (Kotlin)

- **Unit tests** (MockK): Services, DTO mapping, feature flag logic
- **Integration tests** (Testcontainers): Repository tests against real PostgreSQL
- **Web layer tests** (`@WebMvcTest`): Controller + JTE rendering, including partial template responses
- **End-to-end tests**: Full Spring context with Testcontainers (PostgreSQL) and WireMock (mock Python service)

### 16.2 Python (FastAPI)

- **Unit tests** (pytest): Optimization logic with fixed input data and known expected outputs
- **API tests** (TestClient): FastAPI endpoints with fixture data
- **Regression tests**: Known portfolio configurations that must produce allocations within ±0.5% of expected

### 16.3 Mocking External Dependencies

| Dependency | Mock Strategy |
|---|---|
| Python optimizer service | WireMock in Kotlin tests; pre-recorded JSON responses |
| yfinance | Fixture CSV files with 3 years of daily prices for 30 tickers |
| Frankfurter API | Fixture JSON with EUR FX rates |
| PostgreSQL | Testcontainers (real PostgreSQL in Docker) |

### 16.4 Test Data Fixtures

Include a fixture file (`test-portfolio.json`) with the project owner's actual portfolio as a reference test case. The 37-position portfolio with positions across USD, CAD, JPY, GBP, MXN, HKD, and EUR provides excellent multi-currency test coverage. Expected optimization outputs should be pre-computed and stored as golden files.

---

## Appendix A: Mathematical Foundations

### A.1 Multi-Asset Kelly Criterion

Maximize geometric growth rate: `g(f) = r_f + fᵀμ − ½fᵀΣf`

Solution: `f* = Σ⁻¹μ` (full Kelly), `f = (1/γ)Σ⁻¹μ` (fractional Kelly, γ=2 for half-Kelly)

### A.2 Black-Litterman

Posterior returns: `E[μ] = [(τΣ)⁻¹ + PᵀΩ⁻¹P]⁻¹ × [(τΣ)⁻¹Π + PᵀΩ⁻¹Q]`

Where:
- `Π = δΣw_mkt` (equilibrium returns, δ ≈ 2.5)
- `P` = pick matrix (identity for absolute views)
- `Q` = user's expected returns
- `Ω` = view uncertainty (from Idzorek's method)
- `τ` ≈ 0.025–0.05

### A.3 Idzorek Confidence Mapping

For view k with confidence `c_k ∈ (0, 1)`:

`ω_k² = [(1 − c_k)/c_k] × τ × p_kᵀΣp_k`

- At 50%: view and equilibrium get equal weight
- At 80%: view dominates
- At 20%: equilibrium dominates

### A.4 CVaR (Rockafellar-Uryasev)

`min ζ + 1/((1−α)S) Σ_s z_s` subject to `z_s ≥ −wᵀr_s − ζ, z_s ≥ 0, Σw = 1`

Typically α = 0.95 (worst 5% of scenarios).

### A.5 Effective Independent Bets

For N assets with average pairwise correlation ρ̄:

`N_eff = N / [1 + (N−1)ρ̄]`

Example: 4 tech stocks at ρ̄ = 0.65 → N_eff = 4/2.95 = 1.36

### A.6 Ledoit-Wolf Shrinkage

`Σ_shrunk = δF + (1−δ)S`

Where S is sample covariance, F is structured target (constant correlation or single-factor), δ is analytically optimal shrinkage intensity. Essential when N/T > 0.1 (N assets, T observations).

---

## Appendix B: Example Portfolio Data

The primary test case is a portfolio with 37 positions across 7 currencies. The backend stores only percentage weights (the "Current %" column below). Absolute EUR values and share counts are held exclusively in the browser's localStorage for testing purposes.

| Ticker | Name | Currency | Current % |
|---|---|---|---|
| 8PSB | Physical Silver ETC | EUR | 8.4% |
| EGLN | Physical Gold ETC | EUR | 6.6% |
| CSU.TO | Constellation Software | CAD | 12.4% |
| LULU | Lululemon Athletica | USD | 9.3% |
| JCU (4975.T) | JCU Corporation | JPY | 8.3% |
| CYND (4256.T) | CYND Co., Ltd. | JPY | 5.6% |
| AMPX | Amprius Technologies | USD | 4.0% |
| PRE.L | Pensana | GBP | 4.2% |
| HY9H | SK Hynix GDR | EUR | 3.6% |
| GMEXICOB.MX | Grupo Mexico | MXN | 3.5% |
| GOOG | Alphabet | USD | 3.4% |
| NVDA | NVIDIA | USD | 3.2% |
| SKM | SK Telecom ADR | USD | 2.5% |
| MOH | Molina Healthcare | USD | 2.3% |
| ODET.PA | Compagnie de l'Odet | EUR | 2.3% |
| HOOD | Robinhood Markets | USD | 2.0% |
| CHTR | Charter Communications | USD | 1.5% |
| FFH.TO | Fairfax Financial | CAD | 1.5% |
| ANIC.L | Agronomics Ltd | GBP | 1.4% |
| BE | Bloom Energy | USD | 1.4% |
| DFND.L | iShares Global Defense | USD | 1.3% |
| BRK.B | Berkshire Hathaway | USD | 1.3% |
| H4N.HE | Solar Foods | EUR | 1.1% |
| FAST.AS | Fastned | EUR | 1.0% |
| MTH | Meritage Homes | USD | 0.9% |
| JOBY | Joby Aviation | USD | 0.7% |
| 9880.HK | UBTECH Robotics | HKD | 0.6% |
| FLXT | Franklin FTSE Taiwan | EUR | 0.5% |
| BCH | Banco de Chile ADR | USD | 0.5% |
| CEC | Amundi Eastern Europe | EUR | 0.5% |
| TUR | Amundi Turkey | EUR | 0.5% |
| BOTZ | GX Robotics & AI | EUR | 0.5% |
| CIB | Bancolombia ADR | USD | 0.4% |
| CASH.USD | US Dollar Cash | USD | 1.1% |
| CASH.EUR | Euro Cash | EUR | 0.4% |
| CASH.JPY | Japanese Yen Cash | JPY | 0.7% |
| CASH.CAD | Canadian Dollar Cash | CAD | 0.5% |

New candidates to add: TEP.PA (Teleperformance), 3690.HK (Meituan), AMZN (Amazon)

---

## Appendix C: yfinance Ticker Mapping

The `TickerMapper` service maintains a table of internal display names to yfinance-compatible ticker strings. yfinance uses Yahoo Finance's suffix convention:

| Suffix | Exchange | Examples |
|---|---|---|
| (none) | US exchanges (NYSE, NASDAQ) | GOOG, AMZN, LULU, NVDA, HOOD |
| .TO | Toronto Stock Exchange | CSU.TO, FFH.TO |
| .T | Tokyo Stock Exchange | 4256.T, 4975.T |
| .HK | Hong Kong Stock Exchange | 3690.HK, 9880.HK |
| .MX | Mexican Stock Exchange (BMV) | GMEXICOB.MX |
| .PA | Euronext Paris | TEP.PA, ODET.PA |
| .AS | Euronext Amsterdam | FAST.AS |
| .L | London Stock Exchange | PRE.L, ANIC.L, 8PSB.L, EGLN.L, DFND.L |
| .HE | Helsinki Stock Exchange | H4N.HE |
| .DE | Frankfurt/Xetra | HY9H.DE |

For ETFs and ETCs traded on European exchanges, the suffix determines which exchange's listing yfinance retrieves. Some instruments may have multiple listings (e.g., an ETF on both London and Xetra). The `TickerMapper` should default to the most liquid listing or the one matching the user's IBKR holding.

---

*End of specification. This document is self-contained and provides all information needed to implement the portfolio optimizer without access to the original conversation.*
