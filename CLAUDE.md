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
./gradlew :web:bootRun                     # Run app (needs PostgreSQL + optimizer running)

# Optimizer module (Python/FastAPI)
./gradlew :optimizer:test                  # Runs pytest via uv
cd optimizer && uv run pytest              # Direct pytest
cd optimizer && uv run pytest tests/test_optimize.py  # Single test file

# Docker images (JVM images via bootBuildImage/Paketo; optimizer + vault-init via Dockerfile)
./gradlew :web:bootBuildImage              # just the web image (kernfolio-web:latest)
./gradlew dockerBuild                      # production set: web + optimizer + vault-init
./gradlew dockerBuildDev                   # dev set: above + yfinance-twin + frankfurter-twin

# Tailwind CSS is built automatically during :web:processResources
# Requires `tailwindcss` CLI on PATH
```

## Architecture

**Hybrid multi-module app**: Kotlin Spring Boot web server + Python FastAPI optimization microservice + PostgreSQL.

### Modules
- **`web/`** — Spring Boot 4.0.5, Kotlin 2.3.20, Java 25. Server-rendered UI (JTE 3.2.3 `.kte` templates + vanilla JS). Spring Data JDBC (not JPA/Hibernate).
- **`optimizer/`** — FastAPI, Python 3.14+. Stateless: receives all data in request body, no DB access. Endpoints: `/optimize`, `/fetch-prices`, `/fetch-fx-rates`, `/health`.
- **`digital-twins/`** — Mock yfinance and Frankfurter APIs for local dev.

### Data Flow
1. User enters positions (share counts in browser → browser computes `weight_pct` → only percentages sent to backend)
2. Server stores percentage weights in PostgreSQL (absolute values like share counts, total value stay in browser localStorage)
3. Optimization: Spring Boot loads positions + cached prices → assembles request → POST to Python `/optimize` → stores results
4. Results page: server renders metrics/weights, Chart.js fetches data from JSON API endpoints

### Key Patterns
- **JTE templates**: `.kte` files in `web/src/main/jte/`. Full pages in `page/`, partials (for fetch-based DOM updates) in `partial/`. Layout via `@template.layout.base(...)`. Each template declares `@param` for its required model attributes.
- **CSRF for fetch**: `csrf-fetch.js` reads token from `<meta>` tags. All `fetch()` POST/PUT/DELETE must include `Csrf.headers()`.
- **WebClient for optimizer calls**: `optimizerWebClient` bean in `OptimizerClientConfig.kt`, blocking `.block()` pattern (see `YFinanceFetcher.kt`)
- **Jackson 3.x**: Group ID is `tools.jackson` (not `com.fasterxml.jackson`). Annotations still use `com.fasterxml.jackson.annotation`. Snake-case DTOs use `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)`.
- **Ticker mapping**: Internal tickers ↔ yfinance format via `TickerMapper` (e.g., `GMEXICOB` ↔ `GMEXICOB.MX`)
- **Currency**: Never hardcode EUR or any currency. Use `portfolio.baseCurrency`. FX rates stored as EUR/X pairs internally; `FxRateService.getLatestCrossRate(base, target)` computes any pair.
- **JSONB columns**: `OptimizationParameters` and `OptimizationResults` use custom read/write converters in `JsonbConverters.kt`
- **Feature flags**: DB-driven via `FeatureFlagService`, available in templates as `featureFlags.isEnabled('FLAG_NAME')`

### Security
- **Nonce-based CSP**: `CspNonceFilter` generates a per-request nonce. Every `<script>` and `<link rel="stylesheet">` tag must include `nonce="${nonce}"`. CSP header: `script-src 'self' 'nonce-...'`. No `unsafe-eval` or `unsafe-inline`. All client-side JS must be in static `.js` files, never inline. Do not use JS frameworks that require `eval()` / `new Function()`.
- **No dynamic data in HTML**: Never embed server data in HTML `data-*` attributes for JavaScript consumption. Use JSON API endpoints instead (XSS prevention). Chart JS files parse IDs from URL path via `ChartUtils.getIdsFromUrl()`.
- **Privacy**: Backend never sees total portfolio value, share counts, or cash amounts. Only percentage weights.
- **Vault** (mandatory): `spring-cloud-starter-vault-config:5.0.1` is always active via the default `vault` profile. Provides dynamic PostgreSQL credentials (Vault database engine, `app` role), KV v2 secrets under `secret/kernfolio`, and PKI-issued mTLS certificates for inter-service traffic. Dev stack uses `vault server -dev`; production uses file storage and requires operator init/unseal.

### Testing
- **Unit tests**: MockK for mocking, no Spring context
- **Integration tests**: `@SpringBootTest` + `@Import(TestcontainersConfiguration::class)` + `@Transactional` for auto-rollback
- **HTTP client tests**: WireMock with fixtures in `src/test/resources/fixtures/wiremock/__files/`
- **Controller tests**: MockMvc with `mockUserDetails(user)` helper from `TestSecurityUtils.kt`

### Database
- PostgreSQL 18, Liquibase migrations in `web/src/main/resources/db/changelog/`
- Spring Data JDBC repositories (not JPA). UUIDs for PKs, `NUMERIC` for financial values, `JSONB` for nested structures.
- `Instrument` entity uses `Persistable<String>` with manual `isNew` flag for upserts.

### Running the Application

Vault is always on. Vault connection, KV import, database engine, and mTLS cert paths are declared directly in `application.yml` — there is no separate `vault` profile; the previous split into `application-vault.yml` was folded back into the main config once Vault became mandatory. Tests disable Vault via a Gradle `systemProperty` in `web/build.gradle.kts`; `VaultProfileIntegrationTest` opts back in via `@SpringBootTest(properties = ...)`.

JVM images (`kernfolio-web`, `kernfolio-yfinance-twin`, `kernfolio-frankfurter-twin`) are produced by Spring Boot's `bootBuildImage` task (Paketo buildpacks) — the compose files reference them by tag, not by `build:` stanza. Non-JVM images (`optimizer`, `vault-init`) are still built from Dockerfiles via `docker compose build`. `./gradlew dockerBuild(Dev)` orchestrates both paths. `docker compose up` will *not* build missing JVM images; run the Gradle task first.

**Development (full stack):**
```bash
./gradlew dockerBuildDev
docker compose -f docker-compose.yml -f docker-compose.dev.yml up
# Browse to http://localhost:8080
```
Starts: Vault (dev mode, token `dev-root-token`, port 8200), vault-init (seeds secrets + PKI certs), PostgreSQL (5432), optimizer (8000), web (8080), digital twins (8081, 8082). Spring profile: `dev` (via `SPRING_PROFILES_ACTIVE` in `docker-compose.dev.yml`). Dynamic DB credentials come from Vault's database engine; mTLS certs are issued by Vault PKI and shared via the `vault_agent_certs` volume.

**Production:**
```bash
./gradlew dockerBuild
VAULT_APP_TOKEN=<real-token> docker compose up -d
# Browse to https://localhost (Caddy TLS on ports 80/443)
```
Uses `docker-compose.yml` only. Vault runs with file storage (persistent); the operator is responsible for initializing and unsealing it on first boot. Caddy terminates TLS (ports 80/443) and reverse-proxies to web via mTLS. All inter-service communication is mTLS with Vault PKI-issued certificates. Spring profile: `prod`.

**Runtime resource budget:** full dev stack runs in ~1.1 GiB steady-state RSS against ~2.4 GiB of `mem_limit`. The JVM services (web, twins) use explicit `JAVA_TOOL_OPTIONS` (e.g. `-Xmx192m -Xss512k -XX:MaxMetaspaceSize=128m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=10m` for web) to suppress Paketo's chunky default reservations. See the README Quickstart table for per-service sizing.

**No HEALTHCHECK on JVM services:** Paketo's default tiny runtime (`run-noble-java-tiny`) is distroless-style — no shell, no wget/curl/nc. The compose DAG doesn't require those services to report healthy (caddy uses a plain `depends_on`). Postgres, Vault, and optimizer keep their working healthchecks.

**Profile summary:**
| Profile | Purpose | Notes |
|---------|---------|-------|
| `dev` | Local dev stack with digital twins for market data. | Disables the scheduled market-data job; precompiled JTE templates. |
| `prod` | Production runtime. | Precompiled JTE templates. |
| `test` | `@SpringBootTest` with H2. | Disables Liquibase, scheduler; only used by `KernfolioApplicationTest`. |

(Vault is not a profile — it's on by default in `application.yml`.)
