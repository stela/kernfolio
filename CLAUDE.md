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
./gradlew :web:bootRun                     # Run app (needs PostgreSQL + optimizer running); plain HTTP on 8080 — the `tls` profile is docker-only. With `dev`, `db-tls` needs the certs on the host: copy them out of the `certs_web` volume (including `openbao-ca.pem`, which Spring Cloud Vault needs for `https://localhost:8200`) and set `OPENBAO_CERTS_DIR` (binds `openbao.certs.dir`)

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
# non-BOM ones (currently just bouncycastle) are `constraints` in web/build.gradle.kts.
# Re-check and delete them on every Spring Boot / Spring Cloud bump — a stale pin once held Jackson *below* the BOM.

# Docker images (JVM images via bootBuildImage/Paketo; optimizer + openbao-init + caddy via Dockerfile)
./gradlew :web:bootBuildImage              # just the web image (kernfolio-web:latest)
./gradlew dockerBuild                      # production set: web + optimizer + openbao-init + caddy
./gradlew dockerBuildDev                   # dev set: above + yfinance-twin + frankfurter-twin

# Tailwind CSS is built automatically during :web:processResources
# Requires `tailwindcss` CLI on PATH
```

## Architecture

**Hybrid multi-module app**: Kotlin Spring Boot web server + Python FastAPI optimization microservice + PostgreSQL.

### Modules
- **`web/`** — Spring Boot 4.1.2, Kotlin 2.4.21, Java 25. Server-rendered UI (JTE 3.2.4 `.kte` templates + vanilla JS). Spring Data JDBC (not JPA/Hibernate).
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
- **Service-to-service mTLS** (all certs from OpenBao PKI, Ed25519), each hop covered by a Testcontainers test:
  - Caddy → web: Tomcat `client-auth: need` + `ClientCertCnFilter` (`CN=caddy` only), `WebMtlsIntegrationTest`.
  - web → optimizer: uvicorn `ssl_cert_reqs=CERT_REQUIRED` (`run_mtls.py`), any cert from the CA (uvicorn doesn't expose the peer cert, so no CN allowlist), `OptimizerMtlsIntegrationTest` (builds the real image). Client cert files must be sent **with their intermediate**: the optimizer trusts only the root, so a leaf-only client (e.g. `generateCertificate` instead of `generateCertificates`, or `openssl s_client -cert` without `-cert_chain`) gets its connection dropped.
  - web / OpenBao / init → postgres: `postgres/docker-entrypoint-tls.sh` + `pg_hba_mtls.conf`, `PostgresTlsIntegrationTest` + `OpenBaoIntegrationTest` (end to end). See Database.
  - **Container hardening:** every service has `cap_drop: ALL` + `no-new-privileges` (the `x-hardened` anchor in `docker-compose.yml`, inlined for the dev twins). Only openbao-tls-init and openbao-init (`CHOWN`, `DAC_READ_SEARCH`) and postgres (`CHOWN`, `DAC_READ_SEARCH`, `FOWNER`, `SETUID`, `SETGID`, measured as the minimum for its root-then-gosu start, first boot and restart) add capabilities back. Postgres's main process still ends up with none. The optimizer image is distroless: Google's `gcr.io/distroless/cc-debian13:nonroot` plus a uv-managed CPython (`.python-version`) copied in from a `debian:trixie-slim` build stage. It has no shell, no package manager and no setuid binaries. Its own `python3` variant is Debian's 3.13, hence the uv Python. No shell also means `docker exec … sh` doesn't work there; use `docker debug` or a debug container.
  - clients → OpenBao (server TLS, token auth, no client certs): `openbao/config/listener.hcl`, TLS 1.3 on 8200. OpenBao's PKI can't issue its own listener cert before it runs, so the one-shot `openbao-tls-init` (`openbao/init/setup-tls.sh`, runs before `openbao`) makes it with openssl from a throwaway bootstrap CA. Clients trust **`openbao-ca.pem`, a different anchor from the PKI's `ca.pem`**: `BAO_CACERT` for the `bao` CLI containers, `spring.cloud.vault.ssl.trust-store` (`PEM`) for web, `curl --cacert` in `scripts/openbao-pg-dump.sh`. The cert is **ECDSA P-256 on purpose**: browsers open the OpenBao UI and reject Ed25519. SANs `openbao`, `localhost`, `127.0.0.1`. The cert is kept across runs and only replaced when it's within 30 days of expiry (2-year lifetime) or its SANs change; clients pick up a new anchor on their next start. `OpenBaoTlsInitIntegrationTest`, `OpenBaoInitIntegrationTest` (compose order: tls-init → OpenBao → setup.sh over TLS), `OpenBaoIntegrationTest` (Spring over TLS, plain HTTP refused, untrusted client rejected). The test `OpenBaoContainer` serves the same `listener.hcl`.
- **OpenBao** (mandatory; the MPL-2.0 fork of HashiCorp Vault, `openbao/openbao:2.7.1`): reached through `spring-cloud-starter-vault-config:5.0.3`, always on via `application.yml` — the library, its `spring.cloud.vault.*` properties, `SPRING_CLOUD_VAULT_TOKEN`, the `vault://` import prefix and the `X-OpenBao-Token` header keep their OpenBao names, and OpenBao's API answers to them. Provides dynamic PostgreSQL credentials (database engine, `app` role), KV v2 secrets under `secret/kernfolio`, and PKI-issued mTLS certificates for inter-service traffic. Dev stack uses `bao server -dev`; production uses single-node Raft storage and requires operator init/unseal. OpenBao 2.7 removed the `file` storage backend, so don't reintroduce it. spring-vault-core must stay ≥ 4.0.4: OpenBao ≥ 2.6 omits `performance_standby` from `sys/health`, which older versions fail to deserialise (spring-vault#1048, guarded by `OpenBaoIntegrationTest`).

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
- **TLS / who may log in** (`postgres/docker-entrypoint-tls.sh`, `pg_hba_mtls.conf`): the wrapper passes `ssl=on`, TLSv1.3 minimum and `hba_file` as `-c` flags (`postgres-key.pem` is already postgres-owned 0600, which postgres requires). The image never reads `/etc/postgresql/conf.d`, which is why the old `postgresql_ssl.conf` was silently ignored and `ssl` was off. TCP logins need TLS + a client cert from the OpenBao CA + password. User `kernfolio` additionally needs cert CN `kernfolio` (`clientcert=verify-full`). Dynamic `v-token-app-…` roles accept any CA cert (`verify-ca`): postgres can't apply a `map=` to `scram-sha-256`, so a random role name can't be tied to a cert. Anything else over TCP is rejected. The prefix is pinned by `username_template` in `setup-db.sh`, because the default template would change with the auth method. Unix-socket `trust` stays for `pg_isready`, initdb scripts and `docker exec psql` recovery.
- **Client quirks with Ed25519 certs:** SCRAM channel binding is impossible (no signature digest to hash the server cert with; libpq says `could not find digest for NID UNDEF`), so every client sets it off: `PGCHANNELBINDING=disable`, `channel_binding=disable` in the OpenBao `connection_url`, pgjdbc `channelBinding: disable`. `verify-full` + client cert already rule out a MITM. libpq **and pgjdbc** (for PEM keys) refuse a client key that isn't owned by the current user at 0600, which is why every key on the volume belongs to its service (see Volumes). pgjdbc also needs `pemKeyAlgorithm: Ed25519`, as it assumes RSA. `scripts/openbao-pg-dump.sh` runs as root and copies web's key to a private temp file.

### Running the Application

OpenBao is always on. OpenBao connection, KV/database engine settings, and mTLS cert paths (`openbao.certs.*`) are declared directly in `application.yml` — there is no separate `vault` profile. The `vault://` config imports and the datasource URL live in `application-dev.yml` / `application-prod.yml`; server-side TLS lives in `application-tls.yml`. Tests disable OpenBao via a Gradle `systemProperty` in `web/build.gradle.kts`; `OpenBaoIntegrationTest` opts back in via `@SpringBootTest(properties = ...)`.

JVM images (`kernfolio-web`, `kernfolio-yfinance-twin`, `kernfolio-frankfurter-twin`) are produced by Spring Boot's `bootBuildImage` task (Paketo buildpacks) — the compose files reference them by tag, not by `build:` stanza. Non-JVM images (`optimizer`, `openbao-init`, `caddy`) are still built from Dockerfiles via `docker compose build`. `./gradlew dockerBuild(Dev)` orchestrates both paths. `docker compose up` will *not* build missing JVM images; run the Gradle task first.

**Development (full stack):**
```bash
./gradlew dockerBuildDev
docker compose -f docker-compose.yml -f docker-compose.dev.yml up
# Browse to https://localhost  (Caddy's built-in CA — trust its root once, see README, or click through)
```
Starts: OpenBao (dev mode, token `dev-root-token`, https on port 8200 — trust `openbao-ca.pem` from `certs_web`), openbao-tls-init (OpenBao's listener cert), openbao-init (seeds secrets + PKI certs), PostgreSQL (5432), optimizer (8000), digital twins (8081, 8082), Caddy (443). web is **not** published — it serves mTLS on 8443 inside the compose network and is reached only through Caddy, same as prod. Spring profiles: `dev,tls` (via `SPRING_PROFILES_ACTIVE` in `docker-compose.dev.yml`). Dynamic DB credentials come from OpenBao's database engine; mTLS certs are issued by OpenBao PKI and handed to each service in its own `certs_<service>` volume.

**Production:**
```bash
./gradlew dockerBuild
BAO_APP_TOKEN=<real-token> KERNFOLIO_DOMAIN=<public-hostname> docker compose up -d
# Browse to https://<public-hostname> (ACME cert). Without KERNFOLIO_DOMAIN: https://localhost, Caddy's built-in CA.
```
Uses `docker-compose.yml` only. OpenBao runs with single-node Raft storage (persistent); the operator is responsible for initializing and unsealing it on first boot. Caddy terminates TLS on 443 (80 only redirects) and reverse-proxies to `https://web:8443` via mTLS. Inter-service traffic is mTLS with OpenBao PKI-issued certificates, except clients → OpenBao itself (see Security). Spring profile: `prod`, which pulls in `tls` and `db-tls` through `spring.profiles.group`.

**Runtime resource budget:** full dev stack runs in ~1.1 GiB steady-state RSS against ~2.4 GiB of `mem_limit`. The JVM services (web, twins) use explicit `JAVA_TOOL_OPTIONS` (e.g. `-Xmx192m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=10m` for web) to suppress Paketo's chunky default reservations. See the README Quickstart table for per-service sizing.

**Front door (Caddy → web):** Caddy is rebuilt from `caddy/Dockerfile` to run as uid 1000 with `cap_drop: ALL`; it listens on 8080/8443 and Docker publishes those as 80/443 (dev: 443 only, via `ports: !override`). The *external* cert is never from OpenBao — OpenBao's PKI is Ed25519, which browsers reject, and dev OpenBao regenerates its root on every restart. Instead `{$KERNFOLIO_DOMAIN:localhost}` gets ACME for a real domain or Caddy's built-in CA for `localhost` (root persisted in `caddy_data`). `Caddyfile.dev` strips HSTS, because HSTS is per host and would force https on every `localhost` port once the root is trusted. The *internal* hop uses OpenBao certs: Caddy presents `caddy-client.pem`, web serves `app.pem` (SANs `app`, `web`, `localhost`). `ClientCertCnFilter` answers 403 by setting the status directly — `sendError()` would ERROR-dispatch to `/error`, which Spring Security turns into a 302 to `/login`. `caddy validate` needs `/openbao/certs` mounted (it loads the client key pair). The `caddy_data`/`caddy_config` volumes must be owned by uid 1000; ones created by the old root-running Caddy have to be removed once.

**No HEALTHCHECK on JVM services:** Paketo's default tiny runtime (`run-noble-java-tiny`) is distroless-style — no shell, no wget/curl/nc. The compose DAG doesn't require those services to report healthy (caddy uses a plain `depends_on`). Postgres, OpenBao, and optimizer keep their working healthchecks.

**Profile summary:**
| Profile | Purpose | Notes |
|---------|---------|-------|
| `dev` | Local dev stack with digital twins for market data. | Disables the scheduled market-data job; precompiled JTE templates. |
| `prod` | Production runtime. | Precompiled JTE templates. |
| `tls` | Server-side mTLS for the Caddy → web hop (`application-tls.yml`). | Port 8443, `client-auth: need`, `ClientCertCnFilter` only admits `CN=caddy`, trusts `X-Forwarded-*`. Grouped into `prod`; listed next to `dev` in the dev compose overlay. Off in tests and bare `bootRun`; covered by `WebMtlsIntegrationTest`. |
| `db-tls` | mTLS to Postgres (`application-db-tls.yml`). | pgjdbc `sslmode=verify-full`, app cert (`pemKeyAlgorithm: Ed25519`), `channelBinding=disable`. Grouped into both `dev` and `prod`; off in tests except `OpenBaoIntegrationTest`. |
| `test` | `@SpringBootTest` with H2. | Disables Liquibase, scheduler; only used by `KernfolioApplicationTest`. |

(OpenBao is not a profile — it's on by default in `application.yml`.)

### OpenBao, PKI & database credentials

OpenBao is the origin for three separate classes of secret material, all seeded by the one-shot `openbao-init` container running `openbao/init/setup.sh`:

**PKI (two-tier chain):** root CA (`CN=Kernfolio Dev CA`) → intermediate CA (`CN=Kernfolio Dev Intermediate CA`) → leaf certs for each service (`app` — web's server cert, SANs `app,web,localhost`, plus its `app-client` identity towards the optimizer; `optimizer`; `caddy` — client-only, for the hop to web; `postgres` — server only; `postgres-admin-client` with `CN=kernfolio`, the superuser's identity for the OpenBao database engine and `openbao-db-init`). All Ed25519: fine for Tomcat/JSSE, Netty, Go, OpenSSL — not for browsers. Leaf files on the volume are written as **leaf + intermediate bundled** so servers present a full chain at handshake; trust anchors only need `ca.pem` (root). Trying to trust only `ca.pem` without the intermediate bundled in the leaf file gives `PKIX path building failed: unable to find valid certification path to requested target`.

`setup.sh` is **idempotent** about root and intermediate: it reads the existing cert first and only generates if absent. Regenerating unconditionally is a trap because OpenBao's `pki/root/generate/internal` **appends** a new issuer rather than overwriting, and `default` stays pinned to the first-ever root. Subsequent `sign-intermediate` calls then sign with the stale default while `ca.pem` on disk gets overwritten by the newer root — the chain fragments silently, and services see `PKIX path validation failed: Path does not chain with any of the trust anchors`. Leaf certs are re-issued every run (short-lived, cheap).

**KV v2 secrets:** `secret/kernfolio` holds app-wide settings (SMTP, session signing key, admin email). Fetched at boot by Spring Cloud Vault. Values are hardcoded in `setup.sh` for dev; in prod they're operator-managed.

**Database credentials (two-stage):**
1. **Superuser password** is bootstrapped by openbao-init as `pg_root_password` into two volumes: `certs_postgres` (owned by postgres) and `certs_openbao` (owned by uid 100). Postgres reads it via `POSTGRES_PASSWORD_FILE` at initdb time, and `openbao-db-init` reads its copy to authenticate as superuser when wiring up the database engine. `setup.sh` **reuses** the existing password (OpenBao's copy, else postgres's) if non-empty and rewrites both copies, which is critical for restart safety.
2. **Dynamic app roles** are issued on demand by OpenBao's database engine (`database/roles/app`). Each role is a real Postgres role with an OpenBao-generated name (`v-token-app-<id>`) and a lease of `default_ttl=1h` / `max_ttl=24h`. Spring Cloud Vault renews until `max_ttl`, then must rotate to a new role. **Laptop sleep** crossing `max_ttl` kills rotation (JVM scheduler frozen while wall-clock advances past the revoke window) — symptom is `FATAL: password authentication failed for user "v-token-app-..."`; fix is `docker compose restart web`.

**Volumes and what lives where:**
- `openbao_data`: OpenBao's Raft storage, mounted at `/openbao/file` (the image pre-creates that dir owned by the `openbao` user; a fresh volume at a new path would be root-owned). In dev mode the volume exists but is ignored — state is in-memory.
- `certs_web`, `certs_caddy`, `certs_optimizer`, `certs_postgres`, `certs_openbao`: **one secrets volume per consumer**, mounted read-only at `/openbao/certs` into that service only (`certs_openbao` into both the OpenBao server, for the database engine's client cert, and `openbao-db-init`). So even root inside one container can't read another service's key. Only `openbao-init` mounts them all, at `/openbao/certs/<service>`; it holds the root token anyway (`openbao-tls-init` mounts just `certs_openbao` and `certs_web`). Each volume has `ca.pem` plus that service's certs and keys (and `pg_root_password` in the postgres and openbao ones; `openbao.pem`/`openbao-key.pem`/`openbao-ca.pem` in openbao's, `openbao-ca.pem` in web's). Inside, private keys and the password belong to the service's uid at 0600, as libpq/pgjdbc require: web 1002, caddy 1000, optimizer 65532 (distroless `nonroot`), postgres 70, OpenBao 100. Certs are `root` 0644. `openbao-init` runs as root with only `CHOWN` + `DAC_READ_SEARCH` (read back its own earlier files) and `no-new-privileges`. Without `DAC_OVERRIDE` it can't open others' files for writing, so `place()` in `openbao/init/common.sh` (sourced by `setup.sh` and `setup-tls.sh`) writes a temp file and renames it. The uids are pinned with `user:` in `docker-compose.yml` and listed in `common.sh`: **change both together**. `OpenBaoInitIntegrationTest` runs the real script with compose's user and caps and checks every volume's exact contents, owners and modes; `ComposeHardeningTest` checks the mounts, uids and capabilities in the compose files.
- `pgdata`: Postgres data directory, including the superuser password baked into the catalog at initdb time.

**`certs_postgres`/`certs_openbao` and `pgdata` are coupled**: the superuser password in `pgdata`'s catalog must equal the bytes in `pg_root_password`. Wiping one without wiping the other desyncs them — postgres boots with the old password while openbao-init generates a fresh one, and `openbao-db-init` then fails to authenticate as superuser so no dynamic app roles can ever be issued. If you must wipe one, wipe both, or reset the postgres superuser password in-place (start postgres alone, `docker exec -u postgres psql` via the `trust`-auth local socket in `pg_hba_mtls.conf`, `ALTER USER kernfolio PASSWORD '<same as the file>'`).

**Dev vs. production differences:**
- Dev (`docker-compose.dev.yml` overlay, `bao server -dev … -config=listener.hcl`): dev mode appends the config file's TLS listener to its own plain one, which is moved to `127.0.0.1:8210` inside the container (it can't be turned off). Don't pass `openbao.hcl` there: its `storage` block would replace dev's in-memory storage. OpenBao state lives in memory, auto-unsealed, root token is `dev-root-token`. Any `docker compose restart openbao` or `docker compose down` wipes PKI + KV + database engine config. OpenBao-init re-runs on next boot and re-seeds everything; because `setup.sh` is idempotent and reuses `pg_root_password` from the volume, a clean cycle is safe. Certs in the `certs_*` volumes persist across restarts and are reused.
- Production (`docker-compose.yml` only, `bao server -config=/openbao/config/openbao.hcl -config=/openbao/config/listener.hcl`): OpenBao uses single-node Raft storage (`openbao_data` volume), persists across restarts, requires operator `bao operator init` + `bao operator unseal` on first boot (and unseal after every restart). PKI + KV + database engine config survive restarts, so openbao-init's idempotency guard short-circuits cleanly on re-runs.

**Triaging a cert-related error after deploy:** compare container start times to volume file mtimes — any service started *before* the latest openbao-init cert write is serving or trusting stale material.
```bash
docker inspect kernfolio-<svc>-1 --format '{{.State.StartedAt}}'
docker run --rm -v kernfolio_certs_optimizer:/v alpine stat -c '%y %n' /v/ca.pem /v/optimizer.pem
```
If timestamps disagree, `docker compose restart <svc>` fixes it.
