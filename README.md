# Kernfolio

Self-hosted, multi-user portfolio optimizer that never learns how much money you have. Each user's portfolios are isolated from every other user's in the database, and only percentage weights — never share counts, cash, or total value — leave the browser.

## What it does

Kernfolio helps you turn your own intrinsic-value views into a mathematically optimal stock portfolio. You enter positions (with ticker search), add an intrinsic value estimate and a confidence level for the ones you have a view on, and the app computes an allocation with the **Black-Litterman** model: market-cap-implied equilibrium returns are blended with your views, weighted by Idzorek confidence, and the resulting posterior is optimized for maximum Sharpe ratio under your min/max weight constraints. Covariance is estimated with Ledoit-Wolf shrinkage or the plain sample covariance.

Each run reports expected return, volatility, Sharpe ratio and 95% CVaR, and charts the proposed allocation against your current one, the correlation matrix, and (behind a feature flag) the efficient frontier. Prices and FX rates are fetched and cached server-side; positions can be in any currency and are converted to the portfolio's base currency. The browser then turns the target weights back into concrete share counts to buy or sell.

**Privacy by construction.** Share counts, cash amounts, and total portfolio value stay in your browser's local storage. The browser converts them to percentage weights before anything is sent, so the backend — and whoever operates it — only ever sees tickers and percentages, and cannot tell a €5,000 portfolio from a €5,000,000 one.

**Multi-user, strictly separated.** One instance serves many users. Registration is invite-only by default (or open, via a feature flag), and an admin manages users and flags. Isolation is enforced in PostgreSQL itself with row-level security on portfolios, positions and optimization runs — every connection is scoped to the signed-in user — rather than relying only on application-level filtering. Database credentials are short-lived and issued by Vault, and all traffic between services is mutual TLS.

## Architecture

| Module | Stack | Purpose |
| --- | --- | --- |
| `web/` | Kotlin 2.4.20 / Spring Boot 4.1.1 / Java 25, JTE + vanilla JS + Chart.js | Server-rendered UI, PostgreSQL persistence, auth, feature flags |
| `optimizer/` | Python 3.14+ / FastAPI | Stateless math and market-data microservice (`/optimize`, `/fetch-prices`, `/fetch-fx-rates`, `/search-tickers`) |
| `digital-twins/` | Spring Boot | Fake yfinance & Frankfurter APIs for local development |
| `vault/`, `postgres/`, `scripts/` | HashiCorp Vault 1.19 + Postgres 18 config | Dynamic DB credentials, mTLS PKI, ops scripts |
| `caddy/`, `Caddyfile*` | Caddy 2 (non-root) | Public HTTPS front door; reverse-proxies to `web` over mTLS |

## Quickstart

Vault is always in the critical path — it provisions database credentials, KV secrets, and PKI certificates for mTLS. The dev stack runs Vault in `-dev` mode so there's nothing to initialize manually.

Two steps, because the JVM images are produced by Gradle (Spring Boot's `bootBuildImage` / Paketo buildpacks) before Compose runs them:

```
./gradlew dockerBuildDev                                          # build images
docker compose -f docker-compose.yml -f docker-compose.dev.yml up # run stack
```

Open https://localhost. Caddy terminates TLS there with a certificate from its own built-in CA, so the browser warns until you trust that CA's root (once — it lives in the `caddy_data` volume and survives restarts):

```
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt caddy-dev-root.crt
sudo security add-trusted-cert -d -r trustRoot -k /Library/Keychains/System.keychain caddy-dev-root.crt   # macOS
sudo cp caddy-dev-root.crt /usr/local/share/ca-certificates/ && sudo update-ca-certificates                       # Debian/Ubuntu (Firefox/Chrome keep their own stores)
```

or just click through the warning. The web app itself is not published: it serves mTLS on 8443 inside the compose network and only accepts Caddy's client certificate, exactly as in production.

Services, ports, and resource footprint:

| Service | Port | `mem_limit` | Typical RSS | Image | Image size |
| --- | --- | --- | --- | --- | --- |
| web | 8443 (internal only, mTLS) | 512 MiB | ~345 MiB | `kernfolio-web:latest` (buildpack) | ~472 MB |
| optimizer | 8000 | 512 MiB | ~370 MiB | `kernfolio-optimizer:latest` (python:3.14-slim) | ~505 MB |
| postgres | 5432 | 256 MiB | ~48 MiB | `postgres:18-alpine` | ~281 MB |
| vault | 8200 | 512 MiB | ~70 MiB | `hashicorp/vault:1.19` | ~488 MB |
| yfinance-twin | 8081 | 256 MiB | ~140 MiB | `kernfolio-yfinance-twin:latest` (buildpack) | ~353 MB |
| frankfurter-twin | 8082 | 256 MiB | ~125 MiB | `kernfolio-frankfurter-twin:latest` (buildpack) | ~352 MB |
| caddy | 443 (dev); 80 + 443 (prod) | 64 MiB | ~17 MiB | `kernfolio-caddy:latest` (caddy:2-alpine, non-root) | ~104 MB |

Full dev stack budget: ~2.4 GiB of container memory, observed steady-state ~1.1 GiB. JVM services size their heap + metaspace + code cache explicitly through `JAVA_TOOL_OPTIONS` (see `docker-compose.yml` / `docker-compose.dev.yml`) so Paketo's memory calculator defers to the explicit values.

All inter-service traffic — including Caddy → web — is mTLS with certificates issued by Vault's PKI engine. Caddy runs as uid 1000 with all capabilities dropped; it listens on 8080/8443 inside its container and Docker publishes those as 80/443.

> **Upgrading an existing checkout:** Caddy used to run as root, so `caddy_data` / `caddy_config` volumes created before this change are root-owned and the new container can't write to them. Remove them once: `docker volume rm kernfolio_caddy_data kernfolio_caddy_config`. (In production, do this before first obtaining a certificate, or `chown -R 1000:1000` the volume contents instead.)

### First login and inviting users

Kernfolio is invite-only by default. On the very first startup (no users in the database) the web app logs a single-use admin invite code at WARN level. Retrieve it from the container logs:

```
docker logs kernfolio-web-1 2>&1 | grep "Admin invite code"
```

Visit https://localhost/register with that code to create the first admin account. If you've already created an admin and lost access, `docker compose down -v` wipes `pgdata` and triggers a fresh bootstrap with a new code on next startup.

Once signed in as admin:

- **Invite further users** at https://localhost/admin/users. The form takes an optional email address: leave it empty to generate a code you hand to the invitee out of band, or fill it in to have the app email the code via the SMTP settings in Vault KV (`secret/kernfolio`). Invitees redeem the code at `/register`, same flow as the initial admin.
- **Open self-registration** (no invite required) by enabling the `SELF_REGISTRATION` feature flag at https://localhost/admin/flags. While the flag is on, `/register` accepts registrations with or without an invite code.

## Production

```
./gradlew dockerBuild                            # build web + optimizer + vault-init + caddy
VAULT_APP_TOKEN=<your-vault-token> KERNFOLIO_DOMAIN=<your.domain> docker compose up -d
```

Uses `docker-compose.yml` only. Vault runs with file storage and must be initialized and unsealed by an operator on first boot. Caddy terminates TLS on 443 (port 80 only redirects to https) and reverse-proxies to the web service over mTLS. With `KERNFOLIO_DOMAIN` set to a public hostname that resolves to the host, Caddy obtains and renews a certificate via ACME; left unset it serves `localhost` from its built-in CA. Copy `.env.example` to `.env` and set `VAULT_APP_TOKEN` and `KERNFOLIO_DOMAIN` before `docker compose up`.

## Build & test

Single command covers every submodule — Kotlin units + Testcontainers integration tests in `web` and the Kotlin digital twins, plus `pytest` in `optimizer`:

```
./gradlew test                    # run every test in every module
./gradlew build                   # compile + test + package everything
./gradlew dockerBuild             # production Docker images (web + optimizer + vault-init + caddy)
./gradlew dockerBuildDev          # dev-stack images (above + both digital twins)
```

`:web:test` needs Docker running (Testcontainers spins up PostgreSQL, Vault, and a Chrome container for the browser tests). `:optimizer:test` shells out to `uv run pytest` — `uv` must be on your `PATH`.

For tighter iteration loops, scope to one module or one test:

```
./gradlew :web:test --tests "*.OptimizerServiceTest*"
./gradlew :optimizer:test
```

### Dependency vulnerability scans

Neither scan is part of `build` or `check` — run them explicitly (e.g. before a release, or after a long pause in development):

```
./gradlew dependencyCheckAggregate   # OWASP Dependency-Check across all JVM modules
./gradlew :optimizer:audit           # pip-audit against the optimizer's Python deps
```

`dependencyCheckAggregate` writes one de-duplicated report for `web` and both digital twins to `build/reports/dependency-check/dependency-check-report.html` (plus `.json`), and fails on any finding with CVSS ≥ 5.0. The plugin is applied and configured once in the root `build.gradle.kts`.

Set an NVD API key in `~/.gradle/gradle.properties` — without one the NVD download is rate-limited to the point of being unusable. OSS Index credentials are optional:

```
nvd.apiKey=<your key from https://nvd.nist.gov/developers/request-an-api-key>
ossIndex.username=<optional>
ossIndex.password=<optional>
```

The first run (and the first after a plugin major-version bump) downloads the full NVD dataset and takes several minutes; later runs are incremental.

## Further reading

- [`agent-specs/PORTFOLIO_OPTIMIZER_SPEC.md`](agent-specs/PORTFOLIO_OPTIMIZER_SPEC.md) — full product and architecture specification (algorithms, schema, security model, deployment).
- [`CLAUDE.md`](CLAUDE.md) — contributor conventions and project-specific guidance for AI coding agents.

## License

Copyright © 2026 Stefan Larsson.

Kernfolio is free software, licensed under the [GNU Affero General Public License v3.0](LICENSE) (`AGPL-3.0-only`). You may use, modify and redistribute it, but if you distribute a modified version — or let users interact with one over a network — you must make the corresponding source available to them under the same license.

Kernfolio is provided without warranty of any kind. It is a tool for exploring portfolio allocations, **not investment advice**. Market data is fetched through third-party sources (e.g. Yahoo Finance via `yfinance`); you are responsible for complying with those providers' terms of use.
