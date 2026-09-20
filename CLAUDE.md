# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Full build (all modules)
./gradlew build

# Web module (Kotlin/Spring Boot)
./gradlew :web:compileKotlin              # Compile only
./gradlew :web:test                        # All tests (needs Docker for Testcontainers)
./gradlew :web:test --tests "com.kernfolio.service.OptimizerServiceTest"  # Single test class
./gradlew :web:test --tests "*.OptimizerServiceTest.CAGR*"               # Test name pattern
./gradlew :web:bootRun                     # Run app (needs PostgreSQL + optimizer running); plain HTTP on 8080 — the `tls` profile is docker-only

# Optimizer module (Python/FastAPI)
./gradlew :optimizer:test                  # Runs pytest via uv
cd optimizer && uv run pytest              # Direct pytest
cd optimizer && uv run pytest tests/test_optimize.py  # Single test file

# Dependency vulnerability scans (NOT part of build/check — run explicitly)
./gradlew dependencyCheckAggregate         # OWASP Dependency-Check, all JVM modules -> build/reports/dependency-check/dependency-check-report.html
./gradlew :optimizer:audit                 # pip-audit via uv
# Plugin is applied + configured once in the root build.gradle.kts (failBuildOnCVSS = 5.0).
# Needs nvd.apiKey in ~/.gradle/gradle.properties. Use Aggregate, not per-module dependencyCheckAnalyze.
# CVE-driven overrides of Spring Boot BOM versions live in the root gradle.properties (jackson.version etc.);
# non-BOM ones (bouncycastle, spring-cloud-context/commons) are `constraints` in web/build.gradle.kts.
# Re-check and delete them on every Spring Boot / Spring Cloud bump — a stale pin once held Jackson *below* the BOM.

# Docker images (JVM images via bootBuildImage/Paketo; optimizer + vault-init + caddy via Dockerfile)
./gradlew :web:bootBuildImage              # just the web image (kernfolio-web:latest)
./gradlew dockerBuild                      # production set: web + optimizer + vault-init + caddy
./gradlew dockerBuildDev                   # dev set: above + yfinance-twin + frankfurter-twin

# Tailwind CSS is built automatically during :web:processResources
# Requires `tailwindcss` CLI on PATH
```

## Architecture

**Hybrid multi-module app**: Kotlin Spring Boot web server + Python FastAPI optimization microservice + PostgreSQL.

### Modules
- **`web/`** — Spring Boot 4.1.1, Kotlin 2.4.20, Java 25. Server-rendered UI (JTE 3.2.4 `.kte` templates + vanilla JS). Spring Data JDBC (not JPA/Hibernate).
- **`optimizer/`** — FastAPI, Python 3.14+. Stateless: receives all data in request body, no DB access. Endpoints: `/optimize`, `/fetch-prices`, `/fetch-fx-rates`, `/health`.
- **`digital-twins/`** — Mock yfinance and Frankfurter APIs for local dev.

### Data Flow
1. User enters positions (share counts in browser → browser computes `weight_pct` → only percentages sent to backend)
2. Server stores percentage weights in PostgreSQL (absolute values like share counts, total value stay in browser localStorage)
3. Optimization: Spring Boot loads positions + cached prices → assembles request → POST to Python `/optimize` → stores results. A position's *view* is `expected_return` + `return_stddev` (fractions/yr, both or neither — enforced in `PositionForm.validate()`, a DB CHECK, and the optimizer's request validator) and is passed through untouched; there is no IV→return conversion any more.
4. Results page: server renders metrics/weights, Chart.js fetches data from JSON API endpoints

### Key Patterns
- **JTE templates**: `.kte` files in `web/src/main/jte/`. Full pages in `page/`, partials (for fetch-based DOM updates) in `partial/`. Layout via `@template.layout.base(...)`. Each template declares `@param` for its required model attributes.
- **CSRF for fetch**: `csrf-fetch.js` reads token from `<meta>` tags. All `fetch()` POST/PUT/DELETE must include `Csrf.headers()`.
- **WebClient for optimizer calls**: `optimizerWebClient` bean in `OptimizerClientConfig.kt`, blocking `.block()` pattern (see `YFinanceFetcher.kt`)
- **Jackson 3.x**: Group ID is `tools.jackson` (not `com.fasterxml.jackson`). Annotations still use `com.fasterxml.jackson.annotation`. Snake-case DTOs use `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)`.
- **Ticker mapping**: Internal tickers ↔ yfinance format via `TickerMapper` (e.g., `GMEXICOB` ↔ `GMEXICOB.MX`)
- **Currency**: Never hardcode EUR or any currency. Use `portfolio.baseCurrency`. FX rates stored as EUR/X pairs internally; `FxRateService.getLatestCrossRate(base, target)` computes any pair.
- **JSONB columns**: `OptimizationParameters` and `OptimizationResults` use custom read/write converters in `JsonbConverters.kt`
- **Views & sizing** (`optimizer/app/optimizer.py`): Black-Litterman with explicit `omega = tau * stddev²` (not Idzorek), so a view's weight against the market prior is ≈ `σ² / (σ² + stddev²)` — returned as `view_confidences`. Then fractional Kelly as a cvxpy QP: `max w·(μ−rf) − 1/(2f)·wᵀΣw`, `Σw ≤ 1`, position bounds. **Weights are fractions of the whole portfolio** and sum to `1 − cash_weight`; never renormalise them to 1. `min_weight > 0` puts a floor of `n·min_weight` under equity exposure. Tickers without market cap sit outside BL at `min_weight`. `ChartDataController.allocationData` spreads `cashWeight` over the `CASH.<CUR>` positions; runs stored before this have `cashWeight = null` and leave cash alone.
- **Feature flags**: DB-driven via `FeatureFlagService`, available in templates as `featureFlags.isEnabled('FLAG_NAME')`

### Security
- **Nonce-based CSP**: `CspNonceFilter` generates a per-request nonce. Every `<script>` and `<link rel="stylesheet">` tag must include `nonce="${nonce}"`. CSP header: `script-src 'self' 'nonce-...'`. No `unsafe-eval` or `unsafe-inline`. All client-side JS must be in static `.js` files, never inline. Do not use JS frameworks that require `eval()` / `new Function()`.
- **No dynamic data in HTML**: Never embed server data in HTML `data-*` attributes for JavaScript consumption. Use JSON API endpoints instead (XSS prevention). Chart JS files parse IDs from URL path via `ChartUtils.getIdsFromUrl()`.
- **Privacy**: Backend never sees total portfolio value, share counts, or cash amounts. Only percentage weights.
- **Vault** (mandatory): `spring-cloud-starter-vault-config:5.0.2` is always active via the default `vault` profile. Provides dynamic PostgreSQL credentials (Vault database engine, `app` role), KV v2 secrets under `secret/kernfolio`, and PKI-issued mTLS certificates for inter-service traffic. Dev stack uses `vault server -dev`; production uses file storage and requires operator init/unseal.

### Testing
- **Unit tests**: MockK for mocking, no Spring context
- **Integration tests**: `@SpringBootTest` + `@Import(TestcontainersConfiguration::class)` + `@Transactional` for auto-rollback
- **HTTP client tests**: WireMock with fixtures in `src/test/resources/fixtures/wiremock/__files/`
- **Controller tests**: MockMvc with `mockUserDetails(user)` helper from `TestSecurityUtils.kt`

### Database
- PostgreSQL 18, Liquibase migrations in `web/src/main/resources/db/changelog/`
- Spring Data JDBC repositories (not JPA). UUIDs for PKs, `NUMERIC` for financial values, `JSONB` for nested structures.
- `Instrument` entity uses `Persistable<String>` with manual `isNew` flag for upserts.
- **`PGDATA` is set explicitly to `/var/lib/postgresql/data`** in `docker-compose.yml`. The official `postgres:18-alpine` image defaults `PGDATA` to `/var/lib/postgresql/18/docker`, which is outside our named `pgdata` volume mount — without the override, the cluster lands in Docker's anonymous VOLUME at `/var/lib/postgresql` and gets silently orphaned (and replaced with a fresh initdb) on every `docker compose down && up` cycle, leaving dangling volumes behind and re-triggering `AdminBootstrapRunner`. Don't remove the `PGDATA:` env line.

### Running the Application

Vault is always on. Vault connection, KV/database engine settings, and mTLS cert paths (`vault.certs.*`) are declared directly in `application.yml` — there is no separate `vault` profile. The `vault://` config imports and the datasource URL live in `application-dev.yml` / `application-prod.yml`; server-side TLS lives in `application-tls.yml`. Tests disable Vault via a Gradle `systemProperty` in `web/build.gradle.kts`; `VaultProfileIntegrationTest` opts back in via `@SpringBootTest(properties = ...)`.

JVM images (`kernfolio-web`, `kernfolio-yfinance-twin`, `kernfolio-frankfurter-twin`) are produced by Spring Boot's `bootBuildImage` task (Paketo buildpacks) — the compose files reference them by tag, not by `build:` stanza. Non-JVM images (`optimizer`, `vault-init`, `caddy`) are still built from Dockerfiles via `docker compose build`. `./gradlew dockerBuild(Dev)` orchestrates both paths. `docker compose up` will *not* build missing JVM images; run the Gradle task first.

**Development (full stack):**
```bash
./gradlew dockerBuildDev
docker compose -f docker-compose.yml -f docker-compose.dev.yml up
# Browse to https://localhost  (Caddy's built-in CA — trust its root once, see README, or click through)
```
Starts: Vault (dev mode, token `dev-root-token`, port 8200), vault-init (seeds secrets + PKI certs), PostgreSQL (5432), optimizer (8000), digital twins (8081, 8082), Caddy (443). web is **not** published — it serves mTLS on 8443 inside the compose network and is reached only through Caddy, same as prod. Spring profiles: `dev,tls` (via `SPRING_PROFILES_ACTIVE` in `docker-compose.dev.yml`). Dynamic DB credentials come from Vault's database engine; mTLS certs are issued by Vault PKI and shared via the `vault_agent_certs` volume.

**Production:**
```bash
./gradlew dockerBuild
VAULT_APP_TOKEN=<real-token> KERNFOLIO_DOMAIN=<public-hostname> docker compose up -d
# Browse to https://<public-hostname> (ACME cert). Without KERNFOLIO_DOMAIN: https://localhost, Caddy's built-in CA.
```
Uses `docker-compose.yml` only. Vault runs with file storage (persistent); the operator is responsible for initializing and unsealing it on first boot. Caddy terminates TLS on 443 (80 only redirects) and reverse-proxies to `https://web:8443` via mTLS. All inter-service communication is mTLS with Vault PKI-issued certificates. Spring profile: `prod`, which pulls in `tls` through `spring.profiles.group`.

**Runtime resource budget:** full dev stack runs in ~1.1 GiB steady-state RSS against ~2.4 GiB of `mem_limit`. The JVM services (web, twins) use explicit `JAVA_TOOL_OPTIONS` (e.g. `-Xmx192m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=10m` for web) to suppress Paketo's chunky default reservations. See the README Quickstart table for per-service sizing.

**Front door (Caddy → web):** Caddy is rebuilt from `caddy/Dockerfile` to run as uid 1000 with `cap_drop: ALL`; it listens on 8080/8443 and Docker publishes those as 80/443 (dev: 443 only, via `ports: !override`). The *external* cert is never from Vault — Vault's PKI is Ed25519, which browsers reject, and dev Vault regenerates its root on every restart. Instead `{$KERNFOLIO_DOMAIN:localhost}` gets ACME for a real domain or Caddy's built-in CA for `localhost` (root persisted in `caddy_data`). `Caddyfile.dev` strips HSTS, because HSTS is per host and would force https on every `localhost` port once the root is trusted. The *internal* hop uses Vault certs: Caddy presents `caddy-client.pem`, web serves `app.pem` (SANs `app`, `web`, `localhost`). `ClientCertCnFilter` answers 403 by setting the status directly — `sendError()` would ERROR-dispatch to `/error`, which Spring Security turns into a 302 to `/login`. `caddy validate` needs `/vault/certs` mounted (it loads the client key pair). The `caddy_data`/`caddy_config` volumes must be owned by uid 1000; ones created by the old root-running Caddy have to be removed once.

**No HEALTHCHECK on JVM services:** Paketo's default tiny runtime (`run-noble-java-tiny`) is distroless-style — no shell, no wget/curl/nc. The compose DAG doesn't require those services to report healthy (caddy uses a plain `depends_on`). Postgres, Vault, and optimizer keep their working healthchecks.

**Profile summary:**
| Profile | Purpose | Notes |
|---------|---------|-------|
| `dev` | Local dev stack with digital twins for market data. | Disables the scheduled market-data job; precompiled JTE templates. |
| `prod` | Production runtime. | Precompiled JTE templates. |
| `tls` | Server-side mTLS for the Caddy → web hop (`application-tls.yml`). | Port 8443, `client-auth: need`, `ClientCertCnFilter` only admits `CN=caddy`, trusts `X-Forwarded-*`. Grouped into `prod`; listed next to `dev` in the dev compose overlay. Off in tests and bare `bootRun`; covered by `WebMtlsIntegrationTest`. |
| `test` | `@SpringBootTest` with H2. | Disables Liquibase, scheduler; only used by `KernfolioApplicationTest`. |

(Vault is not a profile — it's on by default in `application.yml`.)

### Vault, PKI & database credentials

Vault is the origin for three separate classes of secret material, all seeded by the one-shot `vault-init` container running `vault/init/setup.sh`:

**PKI (two-tier chain):** root CA (`CN=Kernfolio Dev CA`) → intermediate CA (`CN=Kernfolio Dev Intermediate CA`) → leaf certs for each service (`app` — web's server cert, SANs `app,web,localhost`, plus its `app-client` identity towards the optimizer; `optimizer`; `caddy` — client-only, for the hop to web; `postgres`). All Ed25519: fine for Tomcat/JSSE, Netty, Go, OpenSSL — not for browsers. Leaf files on the volume are written as **leaf + intermediate bundled** so servers present a full chain at handshake; trust anchors only need `ca.pem` (root). Trying to trust only `ca.pem` without the intermediate bundled in the leaf file gives `PKIX path building failed: unable to find valid certification path to requested target`.

`setup.sh` is **idempotent** about root and intermediate: it reads the existing cert first and only generates if absent. Regenerating unconditionally is a trap because Vault's `pki/root/generate/internal` **appends** a new issuer rather than overwriting, and `default` stays pinned to the first-ever root. Subsequent `sign-intermediate` calls then sign with the stale default while `ca.pem` on disk gets overwritten by the newer root — the chain fragments silently, and services see `PKIX path validation failed: Path does not chain with any of the trust anchors`. Leaf certs are re-issued every run (short-lived, cheap).

**KV v2 secrets:** `secret/kernfolio` holds app-wide settings (SMTP, session signing key, admin email). Fetched at boot by Spring Cloud Vault. Values are hardcoded in `setup.sh` for dev; in prod they're operator-managed.

**Database credentials (two-stage):**
1. **Superuser password** is bootstrapped by vault-init into `/vault/certs/pg_root_password` on the `vault_agent_certs` volume. Postgres reads it via `POSTGRES_PASSWORD_FILE` at initdb time, and `vault-db-init` reads the same file to authenticate as superuser when wiring up the database engine. `setup.sh` **reuses** the existing password file if non-empty — critical for restart safety.
2. **Dynamic app roles** are issued on demand by Vault's database engine (`database/roles/app`). Each role is a real Postgres role with a Vault-generated name (`v-token-app-<id>`) and a lease of `default_ttl=1h` / `max_ttl=24h`. Spring Cloud Vault renews until `max_ttl`, then must rotate to a new role. **Laptop sleep** crossing `max_ttl` kills rotation (JVM scheduler frozen while wall-clock advances past the revoke window) — symptom is `FATAL: password authentication failed for user "v-token-app-..."`; fix is `docker compose restart web`.

**Volumes and what lives where:**
- `vault_data`: Vault's storage (file-backed in prod; in dev mode this volume exists but Vault ignores it — state is in-memory).
- `vault_agent_certs`: `ca.pem`, all issued leaf certs, and `pg_root_password`.
- `pgdata`: Postgres data directory, including the superuser password baked into the catalog at initdb time.

**`vault_agent_certs` and `pgdata` are coupled**: the superuser password in `pgdata`'s catalog must equal the bytes in `pg_root_password`. Wiping one without wiping the other desyncs them — postgres boots with the old password while vault-init generates a fresh one, and `vault-db-init` then fails to authenticate as superuser so no dynamic app roles can ever be issued. If you must wipe one, wipe both, or reset the postgres superuser password in-place (start postgres alone, `docker exec -u postgres psql` via the `trust`-auth local socket in `pg_hba_mtls.conf`, `ALTER USER kernfolio PASSWORD '<same as the file>'`).

**Dev vs. production differences:**
- Dev (`docker-compose.dev.yml` overlay, `vault server -dev`): Vault state lives in memory, auto-unsealed, root token is `dev-root-token`. Any `docker compose restart vault` or `docker compose down` wipes PKI + KV + database engine config. Vault-init re-runs on next boot and re-seeds everything; because `setup.sh` is idempotent and reuses `pg_root_password` from the volume, a clean cycle is safe. Certs in `vault_agent_certs` persist across restarts and are reused.
- Production (`docker-compose.yml` only, `vault server -config=/vault/config/vault.hcl`): Vault uses file storage (`vault_data` volume), persists across restarts, requires operator `vault operator init` + `vault operator unseal` on first boot. PKI + KV + database engine config survive restarts, so vault-init's idempotency guard short-circuits cleanly on re-runs.

**Triaging a cert-related error after deploy:** compare container start times to volume file mtimes — any service started *before* the latest vault-init cert write is serving or trusting stale material.
```bash
docker inspect kernfolio-<svc>-1 --format '{{.State.StartedAt}}'
docker run --rm -v kernfolio_vault_agent_certs:/v alpine stat -c '%y %n' /v/ca.pem /v/optimizer.pem
```
If timestamps disagree, `docker compose restart <svc>` fixes it.
