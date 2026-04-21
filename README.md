# Kernfolio

Self-hosted portfolio optimizer for individual investors.

## What it does

Kernfolio helps you turn your own intrinsic-value views into a mathematically optimal stock portfolio. You enter positions, add an intrinsic value estimate and a confidence level for each one, and the app computes optimal allocations using Black-Litterman (with Idzorek confidence), Mean-Variance Markowitz, CVaR, and Hierarchical Risk Parity.

Privacy is built in: share counts, cash amounts, and total portfolio value never leave your browser. The backend only ever sees tickers and percentage weights.

## Architecture

| Module | Stack | Purpose |
| --- | --- | --- |
| `web/` | Kotlin 2.3.20 / Spring Boot 4.0.5 / Java 25, JTE + vanilla JS + Chart.js | Server-rendered UI, PostgreSQL persistence, auth, feature flags |
| `optimizer/` | Python 3.14+ / FastAPI | Stateless math microservice (`/optimize`, `/fetch-prices`, `/fetch-fx-rates`) |
| `digital-twins/` | Spring Boot | Fake yfinance & Frankfurter APIs for local development |
| `vault/`, `postgres/`, `scripts/` | HashiCorp Vault 1.19 + Postgres 18 config | Dynamic DB credentials, mTLS PKI, ops scripts |

## Quickstart (no Vault)

The fastest way to try it locally. Needs Docker and a JDK 25 toolchain.

```
docker compose up postgres optimizer
DB_USER=kernfolio DB_PASSWORD=kernfolio ./gradlew :web:bootRun
```

Open http://localhost:8080.

`.env.example` lists the three environment variables the app reads directly (`DB_USER`, `DB_PASSWORD`, `OPTIMIZER_BASE_URL`); everything else lives in Vault when that profile is enabled.

## Full dev stack (Vault + digital twins)

```
docker compose -f docker-compose.yml -f docker-compose.dev.yml up
```

Services and ports:

| Service | Port |
| --- | --- |
| web | 8080 |
| optimizer | 8000 |
| postgres | 5432 |
| vault (dev mode, token `dev-root-token`) | 8200 |
| yfinance-twin | 8081 |
| frankfurter-twin | 8082 |

All inter-service traffic is mTLS, using certificates issued by Vault's PKI engine. Dynamic database credentials come from Vault's database secrets engine.

## Build & test

Single command covers every submodule — Kotlin units + Testcontainers integration tests in `web` and the Kotlin digital twins, plus `pytest` in `optimizer`:

```
./gradlew test            # run every test in every module
./gradlew build            # compile + test + package everything
```

`:web:test` needs Docker running (Testcontainers spins up PostgreSQL). `:optimizer:test` shells out to `uv run pytest` — `uv` must be on your `PATH`.

For tighter iteration loops, scope to one module or one test:

```
./gradlew :web:test --tests "*.OptimizerServiceTest*"
./gradlew :optimizer:test
```

## Further reading

- [`agent-specs/PORTFOLIO_OPTIMIZER_SPEC.md`](agent-specs/PORTFOLIO_OPTIMIZER_SPEC.md) — full product and architecture specification (algorithms, schema, security model, deployment).
- [`CLAUDE.md`](CLAUDE.md) — contributor conventions and project-specific guidance for AI coding agents.
