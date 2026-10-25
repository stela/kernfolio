# Kernfolio

Self-hosted, multi-user portfolio optimizer that never learns how much money you have. Each user's portfolios are isolated from every other user's in the database, and only percentage weights — never share counts, cash, or total value — leave the browser.

## What it does

Kernfolio takes the conclusions of your own equity research and does the part that is hard to do by hand: sizing the positions *together*, with the correlations between them taken into account.

For each position you have a view on, you enter two numbers: the **annual return you expect** (probability-weighted across your bear/base/bull scenarios) and the **standard deviation** of that return across those scenarios. Positions without a view are fine — the market's estimate is used. (Enter positions with ticker search; prices and FX rates are fetched and cached server-side, and positions can be in any currency.)

1. **Black-Litterman** blends the returns implied by market-cap weights with your views. How much a view counts depends on its spread relative to the stock's own volatility: equal to it, the view and the market meet halfway; tighter, your number dominates; wider, the market's does. The results page shows the weight each view ended up with.
2. **Fractional Kelly sizing** then maximises long-run growth using the full covariance matrix (Ledoit-Wolf shrinkage) under your min/max position limits. Two highly correlated stocks share one risk budget; a diversifier earns extra weight. The **Kelly fraction** (1 = full, 0.5 = half) decides how much of the portfolio stays in **cash** — total exposure is an output, not fixed at 100 %.

Each run reports expected return, volatility, Sharpe ratio, 95 % CVaR and the cash share, and charts the proposed allocation against your current one, the correlation matrix, and (behind a feature flag) the efficient frontier. Applying a result rebalances equities *and* your cash positions, and the browser turns the target weights back into concrete share counts to buy or sell.

**Privacy by construction.** Share counts, cash amounts, and total portfolio value stay in your browser's local storage. The browser converts them to percentage weights before anything is sent, so the backend — and whoever operates it — only ever sees tickers and percentages, and cannot tell a €5,000 portfolio from a €5,000,000 one.

**Multi-user, strictly separated.** One instance serves many users. Registration is invite-only by default (or open, via a feature flag), and an admin manages users and flags. Isolation is enforced in PostgreSQL itself with row-level security on portfolios, positions and optimization runs — every connection is scoped to the signed-in user — rather than relying only on application-level filtering. Database credentials are short-lived and issued by OpenBao, and traffic between the app's services is mutual TLS.

## Architecture

| Module | Stack | Purpose |
| --- | --- | --- |
| `web/` | Kotlin 2.4.21 / Spring Boot 4.1.2 / Java 25, JTE + vanilla JS + Chart.js | Server-rendered UI, PostgreSQL persistence, auth, feature flags |
| `optimizer/` | Python 3.14+ / FastAPI | Stateless math and market-data microservice (`/optimize`, `/fetch-prices`, `/fetch-fx-rates`, `/search-tickers`) |
| `digital-twins/` | Spring Boot | Fake yfinance & Frankfurter APIs for local development |
| `openbao/`, `postgres/`, `scripts/` | OpenBao 2.7 + Postgres 18 config | Dynamic DB credentials, mTLS PKI, ops scripts |
| `caddy/`, `Caddyfile*` | Caddy 2 (non-root) | Public HTTPS front door; reverse-proxies to `web` over mTLS |

## Quickstart

OpenBao (the MPL-licensed, Linux Foundation fork of HashiCorp Vault) is always in the critical path — it provisions database credentials, KV secrets, and PKI certificates for mTLS. The web app talks to it through Spring Cloud Vault, which works unchanged against OpenBao's Vault-compatible API. The dev stack runs OpenBao like production (persistent Raft storage, mutual TLS), except that it unseals and initializes itself, so there's nothing to do by hand. To start it over, remove the `openbao_data` volume.

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
| optimizer | 8000 | 512 MiB | ~370 MiB | `kernfolio-optimizer:latest` (distroless cc + uv-managed CPython 3.14) | ~482 MB |
| postgres | 5432 | 256 MiB | ~48 MiB | `postgres:18-alpine` | ~281 MB |
| openbao | 8200 (TLS) | 512 MiB | ~70 MiB | `openbao/openbao:2.7.1` | ~181 MB |
| yfinance-twin | 8081 | 256 MiB | ~140 MiB | `kernfolio-yfinance-twin:latest` (buildpack) | ~353 MB |
| frankfurter-twin | 8082 | 256 MiB | ~125 MiB | `kernfolio-frankfurter-twin:latest` (buildpack) | ~352 MB |
| caddy | 443 (dev); 80 + 443 (prod) | 64 MiB | ~17 MiB | `kernfolio-caddy:latest` (caddy:2-alpine, non-root) | ~104 MB |

Full dev stack budget: ~2.4 GiB of container memory, observed steady-state ~1.1 GiB. JVM services size their heap + metaspace + code cache explicitly through `JAVA_TOOL_OPTIONS` (see `docker-compose.yml` / `docker-compose.dev.yml`) so Paketo's memory calculator defers to the explicit values.

Traffic between services — Caddy → web, web → optimizer, and every connection to PostgreSQL — is mutual TLS with certificates issued by OpenBao's PKI engine, and each hop has an integration test. PostgreSQL refuses any TCP login without TLS, a client certificate from that CA and a password. Connections to OpenBao itself are mutual TLS too, with certificates from a separate bootstrap CA made before OpenBao starts, since its PKI can't issue them for itself. Each service gets its own secrets volume holding only its certificate and key, so even root inside one container can't read another service's key. Every container runs with all Linux capabilities dropped and `no-new-privileges`; only the one-shot init containers and Postgres's startup add back the few they need. Caddy runs as uid 1000; it listens on 8080/8443 inside its container and Docker publishes those as 80/443.

> **Upgrading an existing checkout:** Caddy used to run as root, so `caddy_data` / `caddy_config` volumes created before this change are root-owned and the new container can't write to them. Remove them once: `docker volume rm kernfolio_caddy_data kernfolio_caddy_config`. (In production, do this before first obtaining a certificate, or `chown -R 1000:1000` the volume contents instead.)

### First login and inviting users

Kernfolio is invite-only by default. On the very first startup (no users in the database) the web app logs a single-use admin invite code at WARN level. Retrieve it from the container logs:

```
docker logs kernfolio-web-1 2>&1 | grep "Admin invite code"
```

Visit https://localhost/register with that code to create the first admin account. If you've already created an admin and lost access, `docker compose down -v` wipes `pgdata` and triggers a fresh bootstrap with a new code on next startup.

Once signed in as admin:

- **Invite further users** at https://localhost/admin/users. The form takes an optional email address: leave it empty to generate a code you hand to the invitee out of band, or fill it in to have the app email the code via the SMTP settings in OpenBao KV (`secret/kernfolio`). Invitees redeem the code at `/register`, same flow as the initial admin.
- **Open self-registration** (no invite required) by enabling the `SELF_REGISTRATION` feature flag at https://localhost/admin/flags. While the flag is on, `/register` accepts registrations with or without an invite code.

## Production

```
./gradlew dockerBuild                            # build web + optimizer + openbao-init + caddy
KERNFOLIO_DOMAIN=<your.domain> docker compose up -d
```

Uses `docker-compose.yml` only. The very first start needs a few manual OpenBao steps; see [OpenBao: first start, restarts, starting over](#openbao-first-start-restarts-starting-over). Caddy terminates TLS on 443 (port 80 only redirects to https) and reverse-proxies to the web service over mTLS. With `KERNFOLIO_DOMAIN` set to a public hostname that resolves to the host, Caddy obtains and renews a certificate via ACME; left unset it serves `localhost` from its built-in CA. `KERNFOLIO_DOMAIN` can also go in `.env` (see `.env.example`).

## OpenBao: first start, restarts, starting over

OpenBao holds every secret the stack runs on, so it has to be **unsealed** (its storage decrypted) and **initialized** (given its first keys and root token) before anything else can start. Dev does both itself; prod leaves them to you.

After that first start, nothing in the stack holds a privileged token. The one-shot `openbao-init` uses the root token once to set OpenBao up. It then hands out two narrow tokens, each in its own secrets volume:

- **web's** token (`certs_web`) can read the app's secrets and get database credentials;
- **cert-renewer's** token (`certs_renewer`) can only issue service certificates.

Both are periodic: they stay valid as long as they're renewed at least every 30 days. Web renews its own, and every run of `openbao-init` renews both.

| | Dev (`docker-compose.dev.yml`) | Prod (`docker-compose.yml`) |
| --- | --- | --- |
| Unsealing | Automatic, with a key file openbao-tls-init writes once (`certs_openbao/dev-unseal.key`) | By hand after every start of the `openbao` container, with the unseal keys from `bao operator init` |
| Initializing | Automatic on first start; root token `dev-root-token` | By hand, once (`bao operator init`) |
| Root token | `dev-root-token`, passed to openbao-init on every `up` | Given once for the bootstrap, then revoked |
| Starting over | Remove the `openbao_data` volume | Remove `openbao_data` and every `certs_*` volume, then follow the first start again |

### Dev

Nothing to do: `docker compose -f docker-compose.yml -f docker-compose.dev.yml up` unseals, initializes and bootstraps OpenBao on the first start, and just unseals on later ones. Restarts and `down`/`up` keep everything, as in prod. To start OpenBao over (a new PKI, new tokens, an empty KV store):

```
docker compose -f docker-compose.yml -f docker-compose.dev.yml down
docker volume rm kernfolio_openbao_data
docker compose -f docker-compose.yml -f docker-compose.dev.yml up
```

### Prod: first start

1. Start OpenBao alone. It comes up sealed:
   ```
   docker compose up -d openbao
   ```
2. Initialize it. This prints the unseal keys (5 by default, 3 needed to unseal) and the root token. Store them safely, **outside this host**: whoever has enough unseal keys and the `openbao_data` volume has every secret.
   ```
   docker compose exec openbao bao operator init
   ```
3. Unseal it, entering a different key each time, until `Sealed` shows `false` (3 times by default):
   ```
   docker compose exec openbao bao operator unseal
   ```
4. Bootstrap the stack with the root token. openbao-init sets up the engines, PKI and policies, mints web's and cert-renewer's tokens, and issues every service certificate. `read -rs` keeps the token out of your shell history:
   ```
   read -rs BAO_INIT_TOKEN && export BAO_INIT_TOKEN
   docker compose up -d
   ```
5. Set the real app secrets (`secret/kernfolio` starts with placeholders for SMTP and the admin email; later runs never overwrite it):
   ```
   docker compose exec -e BAO_TOKEN="$BAO_INIT_TOKEN" openbao \
     bao kv patch secret/kernfolio spring.mail.host=… spring.mail.username=… spring.mail.password=… admin.email=…
   docker compose restart web
   ```
6. Once https://<your.domain> works, revoke the root token and run `up` once more without it. That replaces the init containers, whose config would otherwise still hold the token:
   ```
   docker compose exec -e BAO_TOKEN="$BAO_INIT_TOKEN" openbao bao token revoke -self
   unset BAO_INIT_TOKEN
   docker compose up -d
   ```

### Prod: restarts and host reboots

OpenBao comes back **sealed** after every start of its container, and everything else waits for it. Unseal it, then start the rest:

```
docker compose up -d openbao
docker compose exec openbao bao operator unseal     # repeat until Sealed is false
docker compose up -d
```

No token is needed: openbao-init now issues certificates with cert-renewer's token, and web has its own. Restarting any other service doesn't seal OpenBao.

### Prod: recovery

You need a root token again if:

- the stack was down for more than 30 days, so the scoped tokens expired (openbao-init then fails with `OpenBao isn't bootstrapped yet, or its tokens expired`);
- `certs_web` or `certs_renewer` was lost.

Make a new root token from the unseal keys, then repeat steps 4 and 6 of the first start:

```
docker compose exec openbao bao operator generate-root -init         # prints a nonce and an OTP
docker compose exec openbao bao operator generate-root -nonce=<nonce> # once per unseal key, up to the threshold
docker compose exec openbao bao operator generate-root -decode=<encoded token> -otp=<OTP>
```

Never remove `openbao_data` in prod unless you mean to start over: it holds the PKI, the KV secrets and the database engine. Its data can only be unsealed with the keys from step 2.

## Build & test

Single command covers every submodule — Kotlin units + Testcontainers integration tests in `web` and the Kotlin digital twins, plus `pytest` in `optimizer`:

```
./gradlew test                    # run every test in every module
./gradlew build                   # compile + test + package everything
./gradlew dockerBuild             # production Docker images (web + optimizer + openbao-init + caddy)
./gradlew dockerBuildDev          # dev-stack images (above + both digital twins)
```

`:web:test` needs Docker running (Testcontainers spins up PostgreSQL, OpenBao, and a Chrome container for the browser tests). `:optimizer:test` shells out to `uv run pytest` — `uv` must be on your `PATH`.

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
