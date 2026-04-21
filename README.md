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

## Quickstart

Vault is always in the critical path — it provisions database credentials, KV secrets, and PKI certificates for mTLS. The dev stack runs Vault in `-dev` mode so there's nothing to initialize manually.

```
docker compose -f docker-compose.yml -f docker-compose.dev.yml up
```

Open http://localhost:8080.

Services and ports:

| Service | Port | Notes |
| --- | --- | --- |
| web | 8080 | Spring Boot UI |
| optimizer | 8000 | FastAPI math microservice |
| postgres | 5432 | Credentials come from Vault's database engine; no static DB user/password |
| vault | 8200 | Dev mode, root token `dev-root-token` |
| yfinance-twin | 8081 | Fake market data |
| frankfurter-twin | 8082 | Fake FX rates |

All inter-service traffic is mTLS with certificates issued by Vault's PKI engine.

## Production

```
VAULT_APP_TOKEN=<your-vault-token> docker compose up -d
```

Uses `docker-compose.yml` only. Vault runs with file storage and must be initialized and unsealed by an operator on first boot. Caddy terminates TLS on ports 80/443 and reverse-proxies to the web service over mTLS. Copy `.env.example` to `.env` and set `VAULT_APP_TOKEN` before `docker compose up`.

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
