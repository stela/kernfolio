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
8. [Frontend (Thymeleaf + HTMX + Alpine.js + Chart.js)](#8-frontend)
9. [Market Data Pipeline](#9-market-data-pipeline)
10. [Authentication & Authorization](#10-authentication--authorization)
11. [Feature Flags](#11-feature-flags)
12. [Deployment](#12-deployment)
13. [Security Requirements](#13-security-requirements)
14. [Phased Implementation Roadmap](#14-phased-implementation-roadmap)
15. [Testing Strategy](#15-testing-strategy)
16. [Appendix A: Mathematical Foundations](#appendix-a-mathematical-foundations)
17. [Appendix B: Example Portfolio Data](#appendix-b-example-portfolio-data)
18. [Appendix C: yfinance Ticker Mapping](#appendix-c-yfinance-ticker-mapping)

---

## 1. Project Overview

### 1.1 What This Is

A self-hosted web application that helps individual investors optimize their stock portfolios using mathematically rigorous frameworks (Black-Litterman, multi-asset Kelly criterion, CVaR, Hierarchical Risk Parity). Users create candidate portfolios, input their intrinsic value estimates and confidence levels for each position, and the system computes optimal allocations accounting for correlations between assets.

### 1.2 Key Design Principles

- **Hybrid architecture**: Spring Boot (Kotlin) for the web app; thin Python FastAPI microservice for optimization math
- **Python is stateless**: Fed all data in each REST request from Kotlin. No DB access from Python. Pure function: `(data) → (result)`
- **Server-rendered UI**: Thymeleaf + HTMX + Alpine.js. No SPA, no npm, no build toolchain
- **Security-first**: No inline scripts/styles. Self-hosted JS libraries. HTTP-only session cookies. Strict CSP headers
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
│  (HTMX +      │                │  auto Let's Encrypt certificates    │
│   Alpine.js + │                └──────────┬──────────────────────────┘
│   Chart.js)   │                           │ :8080
└──────────────┘                ┌───────────▼──────────────────────────┐
                                │  Spring Boot (Kotlin)                │
                                │  ─ Thymeleaf server-rendered HTML    │
                                │  ─ REST JSON endpoints for charts    │
                                │  ─ Spring Security (session-based)   │
                                │  ─ Market data caching               │
                                │  ─ Feature flags                     │
                                │  ─ Admin panel                       │
                                └──────┬─────────────┬────────────────┘
                                       │             │
                            PostgreSQL │             │ REST (JSON)
                                       │             │ localhost:8000
                                ┌──────▼──────┐  ┌──▼──────────────────┐
                                │ PostgreSQL   │  │ Python FastAPI       │
                                │ 16           │  │ ─ PyPortfolioOpt     │
                                │              │  │ ─ Riskfolio-Lib      │
                                │ Tables:      │  │ ─ cvxpy              │
                                │ users        │  │ ─ numpy/scipy/pandas │
                                │ portfolios   │  │                      │
                                │ positions    │  │ STATELESS            │
                                │ opt_runs     │  │ No DB access         │
                                │ prices_cache │  │ All data in request  │
                                │ fx_rates     │  └──────────────────────┘
                                │ feature_flags│
                                │ invite_codes │
                                └─────────────┘
```

### 2.2 Communication Pattern

1. User interacts with Thymeleaf-rendered HTML pages via HTMX
2. For chart data, separate `fetch()` calls hit `/api/**` JSON endpoints
3. Both HTML and JSON endpoints authenticated via same Spring Security session cookie
4. When optimization is requested:
   a. Spring Boot loads portfolio positions + cached price history from PostgreSQL
   b. Spring Boot assembles full payload (prices matrix, views, confidences, constraints)
   c. Spring Boot POSTs JSON payload to `http://optimizer:8000/optimize`
   d. Python computes optimization, returns JSON result
   e. Spring Boot stores result in `optimization_runs` table
   f. Spring Boot renders result page via Thymeleaf (or returns JSON for chart endpoints)
5. Market data refresh runs as a scheduled job (daily at 22:00 UTC)

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

**User inputs per position**: Ticker, current shares, 5-year intrinsic value estimate (in local currency), confidence level (0–100%)

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

### 3.7 Discrete Allocation

After computing continuous optimal weights, convert to integer share counts given the portfolio's total value.

**Python**: `pypfopt.DiscreteAllocation(weights, latest_prices, total_portfolio_value=195000)`

Returns: `{ticker: num_shares}` and leftover cash.

### 3.8 Correlation Analysis

For any candidate allocation, compute **effective number of independent bets**: `N_eff = N / [1 + (N−1) × ρ̄]` where ρ̄ is average pairwise correlation within a cluster. Display this metric to users alongside optimization results.

---

## 4. Technology Stack

### 4.1 Spring Boot Application (Primary Backend)

| Component | Choice | Version | Notes |
|---|---|---|---|
| Language | Kotlin | 2.0+ | JVM 21 |
| Framework | Spring Boot | 3.4.x | Web, Security, Data JPA, Actuator |
| Template Engine | Thymeleaf | (bundled) | Server-side HTML rendering |
| HTMX Integration | htmx-spring-boot-thymeleaf | latest | Wim Deblauwe's library (Maven Central) |
| Database Access | Spring Data JPA + Hibernate | (bundled) | PostgreSQL dialect |
| HTTP Client | Spring WebClient (WebFlux) | (bundled) | For calling Python service |
| Resilience | Resilience4j | latest | Circuit breaker for Python service calls |
| Migrations | Liquibase | latest | YAML-based changelogs |
| Build | Gradle (Kotlin DSL) | 8.x | |
| Testing | JUnit 5, MockK, Testcontainers | latest | |

### 4.2 Python Optimization Microservice

| Component | Choice | Version | Notes |
|---|---|---|---|
| Language | Python | 3.11+ | |
| Framework | FastAPI | 0.115+ | With Uvicorn ASGI server |
| Portfolio Optimization | PyPortfolioOpt | 1.6.0 | BL, MVO, HRP, CVaR, discrete allocation |
| Advanced Optimization | Riskfolio-Lib | 7.2+ | Phase 3: CVaR variants, risk budgeting |
| Convex Solver | cvxpy | 1.8+ | Underlying engine (auto-installed) |
| Numerics | numpy, scipy, pandas | latest | Foundation layer |
| Validation | Pydantic | 2.x | Request/response schemas |
| Testing | pytest | latest | |

### 4.3 Frontend (No Build Tools Required)

| Component | Choice | Version | Delivery |
|---|---|---|---|
| Server Interaction | HTMX | 2.0.x | Self-hosted: `/static/vendor/htmx/htmx.min.js` |
| Client-side UI State | Alpine.js | 3.14.x | Self-hosted: `/static/vendor/alpinejs/alpine.min.js` |
| Charts | Chart.js | 4.4.x | Self-hosted: `/static/vendor/chartjs/chart.umd.min.js` |
| CSS Framework | Tailwind CSS (CDN-free) | 3.4.x | Pre-built CSS file, self-hosted |
| Icons (optional) | Lucide | latest | Self-hosted SVG sprites |

**CRITICAL**: All JavaScript/CSS libraries are self-hosted under `src/main/resources/static/vendor/`. No CDN references. No inline `<script>` or `<style>` tags anywhere in the codebase.

### 4.4 Infrastructure

| Component | Choice | Notes |
|---|---|---|
| Database | PostgreSQL 16 | Docker container |
| Reverse Proxy | Caddy 2 | Auto TLS via Let's Encrypt |
| Container Runtime | Docker Compose | 4 services: app, optimizer, postgres, caddy |
| Backup | pg_dump + cron + rclone | Nightly to off-site storage |

---

## 5. Database Schema

### 5.1 Core Tables

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
    cash_reserve_pct NUMERIC(5,4) NOT NULL DEFAULT 0.05,  -- e.g., 0.05 = 5%
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Positions within a portfolio
CREATE TABLE positions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    portfolio_id UUID NOT NULL REFERENCES portfolios(id) ON DELETE CASCADE,
    ticker VARCHAR(20) NOT NULL,         -- yfinance format: "4256.T", "3690.HK", "GOOG"
    name VARCHAR(100),                    -- Human-readable: "CYND Co., Ltd."
    currency CHAR(3) NOT NULL,            -- Original trading currency: "JPY", "HKD", "USD"
    shares NUMERIC(12,4) NOT NULL,        -- Number of shares held
    cost_basis_local NUMERIC(14,4),       -- Total cost in local currency
    -- User's intrinsic value inputs:
    intrinsic_value_local NUMERIC(14,4),  -- 5-year IV per share in local currency
    confidence_pct NUMERIC(5,4),          -- 0.0 to 1.0 (0% to 100%)
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
    USING (user_id = current_setting('app.current_user_id')::UUID);
CREATE POLICY positions_user_isolation ON positions
    USING (portfolio_id IN (
        SELECT id FROM portfolios WHERE user_id = current_setting('app.current_user_id')::UUID
    ));
CREATE POLICY optimization_runs_user_isolation ON optimization_runs
    USING (portfolio_id IN (
        SELECT id FROM portfolios WHERE user_id = current_setting('app.current_user_id')::UUID
    ));
```

### 5.2 JSONB Structure for `optimization_runs.parameters`

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
    "cash_reserve": 0.05
  },
  "covariance_method": "ledoit_wolf",
  "lookback_years": 3
}
```

### 5.3 JSONB Structure for `optimization_runs.results`

```json
{
  "optimized_weights": { "GOOG": 0.097, "AMZN": 0.091, "BRK.B": 0.091, ... },
  "discrete_allocation": { "GOOG": 56, "AMZN": 82, "BRK.B": 32, ... },
  "leftover_cash_eur": 1247.50,
  "trades": [
    { "ticker": "GOOG", "action": "BUY", "shares": 31, "estimated_eur": 8150 },
    { "ticker": "CSU.TO", "action": "SELL", "shares": 5, "estimated_eur": 8050 }
  ],
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
  "total_portfolio_value": 195000,
  "latest_prices": {
    "GOOG": 302.0,
    "AMZN": 200.0,
    "BRK.B": 490.0
  },
  "covariance_method": "ledoit_wolf"
}
```

**Response body** (200 OK):

```json
{
  "weights": { "GOOG": 0.097, "AMZN": 0.091, ... },
  "discrete_shares": { "GOOG": 56, "AMZN": 82, ... },
  "leftover_cash": 1247.50,
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

#### GET /health

Returns `{"status": "ok", "version": "1.0.0"}`.

### 6.3 Implementation Notes

- All prices in the request must be in the same currency (EUR). Spring Boot handles conversion before calling.
- `prices` is a dictionary of ticker → list of daily close prices (EUR-converted), plus a `dates` key. All lists must be the same length.
- For tickers where a position has no view (no intrinsic value estimate), omit from `views` and `confidences`. BL will use equilibrium returns for those positions.
- The `efficient_frontier` field returns ~50 points along the frontier for Chart.js plotting.

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
│   ├── User.kt                    -- JPA entity
│   ├── Portfolio.kt               -- JPA entity
│   ├── Position.kt                -- JPA entity
│   ├── OptimizationRun.kt         -- JPA entity with JSONB columns
│   ├── FeatureFlag.kt             -- JPA entity
│   ├── InviteCode.kt              -- JPA entity
│   ├── CachedPrice.kt             -- JPA entity (composite key)
│   ├── CachedFxRate.kt            -- JPA entity (composite key)
│   └── Instrument.kt              -- JPA entity
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
│   ├── OptimizerService.kt        -- Assembles payload, calls Python, stores result
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
│       └── HealthController.kt    -- /api/health
├── dto/
│   ├── OptimizerRequest.kt        -- Maps to Python service request
│   ├── OptimizerResponse.kt       -- Maps from Python service response
│   └── ChartData.kt               -- JSON DTOs for Chart.js
├── marketdata/
│   ├── YFinanceFetcher.kt         -- Calls yfinance via ProcessBuilder or thin Python helper
│   ├── FrankfurterClient.kt       -- REST client for api.frankfurter.dev
│   └── TickerMapper.kt            -- Maps internal tickers to yfinance format
└── security/
    ├── CustomUserDetailsService.kt -- JPA-backed UserDetailsService
    └── TenantFilter.kt            -- Sets app.current_user_id for RLS
```

### 7.2 Key Design Decisions

**yfinance from Kotlin**: Since yfinance is Python-only, the simplest approach is a tiny Python helper script (`scripts/fetch_prices.py`) invoked via `ProcessBuilder`. This script accepts tickers as args, fetches from yfinance, and prints JSON to stdout. Spring Boot parses the JSON. Alternative: add a `/fetch-prices` endpoint to the Python FastAPI service (cleaner separation, slight extra complexity). Either works; the FastAPI approach is preferred for consistency.

**JSONB columns in JPA**: Use `@Type(JsonBinaryType::class)` from `vladmihalcea/hibernate-types` library. Map to `Map<String, Any>` or dedicated Kotlin data classes with Jackson.

**TenantFilter**: A Spring `OncePerRequestFilter` that runs after authentication. Reads the authenticated user's ID and executes `SET LOCAL app.current_user_id = '{userId}'` on the current JDBC connection. This activates PostgreSQL RLS policies for the remainder of the request.

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

### 8.1 Page Structure

| Route | Template | Description |
|---|---|---|
| `GET /` | `dashboard.html` | User's portfolios list, quick stats |
| `GET /login` | `login.html` | Login form |
| `GET /register` | `register.html` | Register with invite code (feature-flagged) |
| `GET /portfolios/new` | `portfolio-form.html` | Create new portfolio |
| `GET /portfolios/{id}` | `portfolio-detail.html` | View portfolio positions, add/edit/remove |
| `GET /portfolios/{id}/optimize` | `optimize.html` | Select algorithm, set constraints, run optimization |
| `GET /portfolios/{id}/results/{runId}` | `results.html` | View optimization results, charts, trade list |
| `GET /admin` | `admin/dashboard.html` | Admin panel: users, flags, system |
| `GET /admin/users` | `admin/users.html` | User management |
| `GET /admin/flags` | `admin/flags.html` | Feature flag management |

### 8.2 HTMX Patterns Used

- **Add position row**: Button with `hx-get="/portfolios/{id}/positions/new-row" hx-target="#positions-table tbody" hx-swap="beforeend"`
- **Remove position row**: Button with `hx-delete="/portfolios/{id}/positions/{posId}" hx-target="closest tr" hx-swap="outerHTML"`
- **Run optimization**: Form with `hx-post="/portfolios/{id}/optimize" hx-target="#results-container" hx-swap="innerHTML" hx-indicator="#spinner"`
- **Loading spinner**: `<div id="spinner" class="htmx-indicator">Optimizing...</div>`

### 8.3 Chart.js via REST API Pattern

The chart canvas is rendered server-side. A separate self-hosted JS file loads data from a JSON API endpoint and initializes the chart. Example pattern:

**In Thymeleaf template** (`results.html`):
```html
<canvas id="allocation-pie" data-portfolio-id="[[${portfolio.id}]]" data-run-id="[[${run.id}]]"></canvas>
<script src="/static/js/charts/allocation-pie.js"></script>
```

**In `/static/js/charts/allocation-pie.js`**:
```javascript
document.addEventListener('DOMContentLoaded', function() {
    const canvas = document.getElementById('allocation-pie');
    if (!canvas) return;
    const portfolioId = canvas.dataset.portfolioId;
    const runId = canvas.dataset.runId;

    fetch(`/api/portfolios/${portfolioId}/runs/${runId}/allocation-data`)
        .then(r => r.json())
        .then(data => {
            new Chart(canvas, {
                type: 'pie',
                data: {
                    labels: data.labels,
                    datasets: [{ data: data.values, backgroundColor: data.colors }]
                },
                options: { responsive: true, plugins: { legend: { position: 'right' } } }
            });
        });
});
```

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
5. **Trade list table**: Not a chart — Thymeleaf-rendered HTML table with color-coded BUY/SELL rows

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
- **CSRF protection**: Enabled (Spring Security default). Thymeleaf auto-injects CSRF tokens in forms. For HTMX AJAX requests, include CSRF token via meta tag + HTMX config.

### 10.2 Route Protection

```kotlin
@Bean
fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
    .authorizeHttpRequests { auth ->
        auth.requestMatchers("/login", "/register", "/static/**").permitAll()
        auth.requestMatchers("/admin/**").hasRole("ADMIN")
        auth.requestMatchers("/api/**").authenticated()  // JSON endpoints
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

### 10.3 HTMX + CSRF Integration

HTMX sends requests via XMLHttpRequest, which needs the CSRF token. Pattern:

**In Thymeleaf `<head>`** (every page via a layout fragment):
```html
<meta name="csrf-token" th:content="${_csrf.token}">
<meta name="csrf-header" th:content="${_csrf.headerName}">
```

**In a self-hosted JS file** (`/static/js/htmx-csrf.js`):
```javascript
document.addEventListener('DOMContentLoaded', function() {
    const token = document.querySelector('meta[name="csrf-token"]')?.content;
    const header = document.querySelector('meta[name="csrf-header"]')?.content;
    if (token && header) {
        document.body.addEventListener('htmx:configRequest', function(event) {
            event.detail.headers[header] = token;
        });
    }
});
```

### 10.4 Invite-Only Registration

1. Admin creates invite code via admin panel → stored in `invite_codes` table
2. Admin shares code with intended user (email, message, etc.)
3. User navigates to `/register`, enters username/email/password + invite code
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

### 11.3 Usage in Thymeleaf

Expose via `@ControllerAdvice` model attribute or custom Thymeleaf dialect:

```html
<div th:if="${featureFlags.isEnabled('ALGO_CVAR')}">
    <option value="CVAR">CVaR (Expected Shortfall)</option>
</div>
```

---

## 12. Deployment

### 12.1 Docker Compose

```yaml
version: '3.8'

services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: portfolio_optimizer
      POSTGRES_USER: ${DB_USER}
      POSTGRES_PASSWORD: ${DB_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${DB_USER}"]
      interval: 10s
      timeout: 5s
      retries: 5

  optimizer:
    build: ./optimizer
    environment:
      - PYTHONUNBUFFERED=1
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:8000/health"]
      interval: 30s
      timeout: 10s
      retries: 3

  app:
    build: ./app
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/portfolio_optimizer
      SPRING_DATASOURCE_USERNAME: ${DB_USER}
      SPRING_DATASOURCE_PASSWORD: ${DB_PASSWORD}
      OPTIMIZER_BASE_URL: http://optimizer:8000
      SPRING_PROFILES_ACTIVE: prod
    depends_on:
      postgres:
        condition: service_healthy
      optimizer:
        condition: service_healthy

  caddy:
    image: caddy:2-alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
      - caddy_config:/config
    depends_on:
      - app

volumes:
  pgdata:
  caddy_data:
  caddy_config:
```

### 12.2 Caddyfile

```
portfolio.yourdomain.com {
    reverse_proxy app:8080
}
```

### 12.3 Backup Cron

```bash
# /etc/cron.d/portfolio-backup
0 3 * * * root docker exec portfolio-postgres pg_dump -U $DB_USER portfolio_optimizer | gzip > /backups/portfolio-$(date +\%Y\%m\%d).sql.gz
0 4 * * * root find /backups -name "portfolio-*.sql.gz" -mtime +7 -delete
```

---

## 13. Security Requirements

### 13.1 Mandatory

- [ ] **No inline scripts or styles** — all JS in self-hosted `.js` files, all CSS in self-hosted `.css` files
- [ ] **Strict CSP header**: `script-src 'self'; style-src 'self'` — no `unsafe-inline`, no `unsafe-eval`
- [ ] **Self-hosted JS libraries**: Chart.js, HTMX, Alpine.js under `/static/vendor/`
- [ ] **HTTP-only, Secure, SameSite=Lax session cookies**
- [ ] **CSRF protection** on all state-changing requests (forms and HTMX AJAX)
- [ ] **bcrypt password hashing** (cost factor 10+)
- [ ] **RLS on PostgreSQL** for defense-in-depth tenant isolation
- [ ] **HTTPS only** (Caddy auto-TLS)
- [ ] **No secrets in source code** — all via environment variables / `.env` file
- [ ] **Python service not exposed externally** — only reachable via Docker internal network
- [ ] **Input validation** on all user inputs (Kotlin: Jakarta Bean Validation; Python: Pydantic)
- [ ] **Rate limiting** on login attempts (Spring Security's `AuthenticationFailureHandler` with exponential backoff or lockout)

### 13.2 Recommended

- [ ] Security headers: X-Content-Type-Options, X-Frame-Options, Referrer-Policy, Permissions-Policy
- [ ] Dependency vulnerability scanning (Gradle: OWASP Dependency-Check; Python: pip-audit)
- [ ] Audit log for admin actions (user creation, feature flag changes)

---

## 14. Phased Implementation Roadmap

### Phase 1: Core (MVP)

**Goal**: A working portfolio optimizer with Black-Litterman and Markowitz.

1. Project scaffolding: Gradle Kotlin DSL, Spring Boot, Docker Compose, Liquibase migrations
2. Database schema (V1 migration)
3. User entity + Spring Security (session-based login, admin/user roles)
4. Admin panel: create users (invite-only)
5. Python FastAPI service: `/health`, `/optimize` (BL + MVO only)
6. Portfolio CRUD: create portfolio, add/edit/remove positions
7. Market data: yfinance price fetching + caching, Frankfurter FX rates
8. Optimization flow: assemble data → call Python → store results → display
9. Results page: allocation table, trade list, allocation pie chart
10. Feature flags table + service (basic)

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

## 15. Testing Strategy

### 15.1 Spring Boot (Kotlin)

- **Unit tests** (MockK): Services, DTO mapping, feature flag logic
- **Integration tests** (Testcontainers): Repository tests against real PostgreSQL
- **Web layer tests** (`@WebMvcTest`): Controller + Thymeleaf rendering, including HTMX fragment responses
- **End-to-end tests**: Full Spring context with Testcontainers (PostgreSQL) and WireMock (mock Python service)

### 15.2 Python (FastAPI)

- **Unit tests** (pytest): Optimization logic with fixed input data and known expected outputs
- **API tests** (TestClient): FastAPI endpoints with fixture data
- **Regression tests**: Known portfolio configurations that must produce allocations within ±0.5% of expected

### 15.3 Mocking External Dependencies

| Dependency | Mock Strategy |
|---|---|
| Python optimizer service | WireMock in Kotlin tests; pre-recorded JSON responses |
| yfinance | Fixture CSV files with 3 years of daily prices for 30 tickers |
| Frankfurter API | Fixture JSON with EUR FX rates |
| PostgreSQL | Testcontainers (real PostgreSQL in Docker) |

### 15.4 Test Data Fixtures

Include a fixture file (`test-portfolio.json`) with the project owner's actual portfolio as a reference test case. The 36-position portfolio with positions across USD, CAD, JPY, GBP, MXN, HKD, and EUR provides excellent multi-currency test coverage. Expected optimization outputs should be pre-computed and stored as golden files.

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

The primary test case is a €194,817 portfolio with 36 positions across 7 currencies:

| Ticker | Name | Currency | EUR Value | Current % |
|---|---|---|---|---|
| 8PSB | Physical Silver ETC | EUR | 16,367 | 8.4% |
| EGLN | Physical Gold ETC | EUR | 12,847 | 6.6% |
| CSU.TO | Constellation Software | CAD | 24,174 | 12.4% |
| LULU | Lululemon Athletica | USD | 18,211 | 9.3% |
| JCU (4975.T) | JCU Corporation | JPY | 16,177 | 8.3% |
| CYND (4256.T) | CYND Co., Ltd. | JPY | 10,965 | 5.6% |
| AMPX | Amprius Technologies | USD | 7,861 | 4.0% |
| PRE.L | Pensana | GBP | 8,237 | 4.2% |
| HY9H | SK Hynix GDR | EUR | 6,916 | 3.6% |
| GMEXICOB.MX | Grupo Mexico | MXN | 6,853 | 3.5% |
| GOOG | Alphabet | USD | 6,602 | 3.4% |
| NVDA | NVIDIA | USD | 6,316 | 3.2% |
| SKM | SK Telecom ADR | USD | 4,918 | 2.5% |
| MOH | Molina Healthcare | USD | 4,574 | 2.3% |
| ODET.PA | Compagnie de l'Odet | EUR | 4,504 | 2.3% |
| HOOD | Robinhood Markets | USD | 3,857 | 2.0% |
| CHTR | Charter Communications | USD | 2,867 | 1.5% |
| FFH.TO | Fairfax Financial | CAD | 2,894 | 1.5% |
| ANIC.L | Agronomics Ltd | GBP | 2,797 | 1.4% |
| BE | Bloom Energy | USD | 2,707 | 1.4% |
| DFND.L | iShares Global Defense | USD | 2,589 | 1.3% |
| BRK.B | Berkshire Hathaway | USD | 2,575 | 1.3% |
| H4N.HE | Solar Foods | EUR | 2,048 | 1.1% |
| FAST.AS | Fastned | EUR | 1,908 | 1.0% |
| MTH | Meritage Homes | USD | 1,658 | 0.9% |
| JOBY | Joby Aviation | USD | 1,274 | 0.7% |
| 9880.HK | UBTECH Robotics | HKD | 1,150 | 0.6% |
| FLXT | Franklin FTSE Taiwan | EUR | 1,035 | 0.5% |
| BCH | Banco de Chile ADR | USD | 987 | 0.5% |
| CEC | Amundi Eastern Europe | EUR | 933 | 0.5% |
| TUR | Amundi Turkey | EUR | 965 | 0.5% |
| BOTZ | GX Robotics & AI | EUR | 916 | 0.5% |
| CIB | Bancolombia ADR | USD | 865 | 0.4% |
| Cash | (various currencies) | EUR | 5,276 | 2.7% |

New candidates to add: TEP.PA (Teleperformance, €51), 3690.HK (Meituan, HKD 77), AMZN (Amazon, $200)

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
