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

Two steps, because the JVM images are produced by Gradle (Spring Boot's `bootBuildImage` / Paketo buildpacks) before Compose runs them:

```
./gradlew dockerBuildDev                                          # build images
docker compose -f docker-compose.yml -f docker-compose.dev.yml up # run stack
```

Open http://localhost:8080.

Services, ports, and resource footprint:

| Service | Port | `mem_limit` | Typical RSS | Image | Image size |
| --- | --- | --- | --- | --- | --- |
| web | 8080 | 512 MiB | ~345 MiB | `kernfolio-web:latest` (buildpack) | ~472 MB |
| optimizer | 8000 | 512 MiB | ~370 MiB | `kernfolio-optimizer:latest` (python:3.14-slim) | ~505 MB |
| postgres | 5432 | 256 MiB | ~48 MiB | `postgres:18-alpine` | ~281 MB |
| vault | 8200 | 512 MiB | ~70 MiB | `hashicorp/vault:1.19` | ~488 MB |
| yfinance-twin | 8081 | 256 MiB | ~140 MiB | `kernfolio-yfinance-twin:latest` (buildpack) | ~353 MB |
| frankfurter-twin | 8082 | 256 MiB | ~125 MiB | `kernfolio-frankfurter-twin:latest` (buildpack) | ~352 MB |
| caddy | 80, 443 | 64 MiB | ~15 MiB | `caddy:2-alpine` | ~60 MB |

Full dev stack budget: ~2.4 GiB of container memory, observed steady-state ~1.1 GiB. JVM services size their heap + metaspace + code cache explicitly through `JAVA_TOOL_OPTIONS` (see `docker-compose.yml` / `docker-compose.dev.yml`) so Paketo's memory calculator defers to the explicit values.

All inter-service traffic is mTLS with certificates issued by Vault's PKI engine.

### First login and inviting users

Kernfolio is invite-only by default. On the very first startup (no users in the database) the web app logs a single-use admin invite code at WARN level. Retrieve it from the container logs:

```
docker logs kernfolio-web-1 2>&1 | grep "Admin invite code"
```

Visit http://localhost:8080/register with that code to create the first admin account. If you've already created an admin and lost access, `docker compose down -v` wipes `pgdata` and triggers a fresh bootstrap with a new code on next startup.

Once signed in as admin:

- **Invite further users** at http://localhost:8080/admin/users. The form takes an optional email address: leave it empty to generate a code you hand to the invitee out of band, or fill it in to have the app email the code via the SMTP settings in Vault KV (`secret/kernfolio`). Invitees redeem the code at `/register`, same flow as the initial admin.
- **Open self-registration** (no invite required) by enabling the `SELF_REGISTRATION` feature flag at http://localhost:8080/admin/flags. While the flag is on, `/register` accepts registrations with or without an invite code.

## Production

```
./gradlew dockerBuild                            # build web + optimizer + vault-init
VAULT_APP_TOKEN=<your-vault-token> docker compose up -d
```

Uses `docker-compose.yml` only. Vault runs with file storage and must be initialized and unsealed by an operator on first boot. Caddy terminates TLS on ports 80/443 and reverse-proxies to the web service over mTLS. Copy `.env.example` to `.env` and set `VAULT_APP_TOKEN` before `docker compose up`.

## Build & test

Single command covers every submodule — Kotlin units + Testcontainers integration tests in `web` and the Kotlin digital twins, plus `pytest` in `optimizer`:

```
./gradlew test                    # run every test in every module
./gradlew build                   # compile + test + package everything
./gradlew dockerBuild             # production Docker images (web + optimizer + vault-init)
./gradlew dockerBuildDev          # dev-stack images (above + both digital twins)
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
