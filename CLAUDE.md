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

# Tailwind CSS is built automatically during :web:processResources
# Requires `tailwindcss` CLI on PATH
```

## Architecture

**Hybrid multi-module app**: Kotlin Spring Boot web server + Python FastAPI optimization microservice + PostgreSQL.

### Modules
- **`web/`** — Spring Boot 4.0.3, Kotlin 2.3.10, Java 25. Server-rendered UI (Thymeleaf + HTMX + Alpine.js). Spring Data JDBC (not JPA/Hibernate).
- **`optimizer/`** — FastAPI, Python 3.11+. Stateless: receives all data in request body, no DB access. Endpoints: `/optimize`, `/fetch-prices`, `/fetch-fx-rates`, `/health`.
- **`digital-twins/`** — Mock yfinance and Frankfurter APIs for local dev.

### Data Flow
1. User enters positions (share counts in browser → browser computes `weight_pct` → only percentages sent to backend)
2. Server stores percentage weights in PostgreSQL (absolute values like share counts, total value stay in browser localStorage)
3. Optimization: Spring Boot loads positions + cached prices → assembles request → POST to Python `/optimize` → stores results
4. Results page: server renders metrics/weights, Chart.js fetches data from JSON API endpoints

### Key Patterns
- **WebClient for optimizer calls**: `optimizerWebClient` bean in `OptimizerClientConfig.kt`, blocking `.block()` pattern (see `YFinanceFetcher.kt`)
- **Jackson 3.x**: Group ID is `tools.jackson` (not `com.fasterxml.jackson`). Annotations still use `com.fasterxml.jackson.annotation`. Snake-case DTOs use `@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)`.
- **Ticker mapping**: Internal tickers ↔ yfinance format via `TickerMapper` (e.g., `GMEXICOB` ↔ `GMEXICOB.MX`)
- **Currency**: Never hardcode EUR or any currency. Use `portfolio.baseCurrency`. FX rates stored as EUR/X pairs internally; `FxRateService.getLatestCrossRate(base, target)` computes any pair.
- **JSONB columns**: `OptimizationParameters` and `OptimizationResults` use custom read/write converters in `JsonbConverters.kt`
- **Feature flags**: DB-driven via `FeatureFlagService`, available in templates as `featureFlags.isEnabled('FLAG_NAME')`

### Security
- **No dynamic data in HTML**: Never use `th:data-*` attributes to pass server data to JavaScript. Use JSON API endpoints instead (XSS prevention). Chart JS files parse IDs from URL path via `ChartUtils.getIdsFromUrl()`.
- **Privacy**: Backend never sees total portfolio value, share counts, or cash amounts. Only percentage weights.
- **Vault** (optional `vault` profile): `spring-cloud-starter-vault-config:5.0.1` for dynamic DB credentials and KV secrets. Disabled by default (`spring.cloud.vault.enabled=false`).

### Testing
- **Unit tests**: MockK for mocking, no Spring context
- **Integration tests**: `@SpringBootTest` + `@Import(TestcontainersConfiguration::class)` + `@Transactional` for auto-rollback
- **HTTP client tests**: WireMock with fixtures in `src/test/resources/fixtures/wiremock/__files/`
- **Controller tests**: MockMvc with `mockUserDetails(user)` helper from `TestSecurityUtils.kt`

### Database
- PostgreSQL 16, Liquibase migrations in `web/src/main/resources/db/changelog/`
- Spring Data JDBC repositories (not JPA). UUIDs for PKs, `NUMERIC` for financial values, `JSONB` for nested structures.
- `Instrument` entity uses `Persistable<String>` with manual `isNew` flag for upserts.

### Running the Application

**Without Vault (simplest, for local dev):**
```bash
# Start just PostgreSQL and optimizer (plain HTTP, env-var credentials)
docker compose up postgres optimizer
# In another terminal, run the web app directly
DB_USER=kernfolio DB_PASSWORD=kernfolio ./gradlew :web:bootRun
# Browse to http://localhost:8080
```
Vault is disabled by default (`spring.cloud.vault.enabled=false` in `application.yml`). Database credentials come from `DB_USER`/`DB_PASSWORD` env vars (default: `kernfolio`/`kernfolio`).

**Development with Vault (full stack):**
```bash
docker compose -f docker-compose.yml -f docker-compose.dev.yml up
# Browse to http://localhost:8080
```
Starts: Vault (dev mode, token `dev-root-token`, port 8200), vault-init (seeds secrets + PKI certs), PostgreSQL (5432), optimizer (8000), web (8080), digital twins (8081, 8082). The `vault` profile is activated via `SPRING_PROFILES_ACTIVE=dev,vault` in docker-compose.dev.yml. Dynamic DB credentials come from Vault's database engine; mTLS certs are issued by Vault PKI and shared via the `vault_agent_certs` volume.

**Production:**
```bash
VAULT_APP_TOKEN=<real-token> docker compose up -d
# Browse to https://localhost (Caddy TLS on ports 80/443)
```
Uses `docker-compose.yml` only. Vault runs with file storage (persistent). Caddy handles TLS termination (ports 80/443) and reverse-proxies to web via mTLS. All inter-service communication is mTLS with Vault PKI-issued certificates. The `vault` Spring profile enables `spring-cloud-starter-vault-config:5.0.1` for automatic DB credential rotation and KV v2 secret injection.

**Profile summary:**
| Profile | Vault | DB Credentials | Optimizer URL | mTLS |
|---------|-------|----------------|---------------|------|
| (none) | Disabled | Env vars `DB_USER`/`DB_PASSWORD` | `http://localhost:8000` | No |
| `vault` | Enabled | Dynamic from Vault database engine | `https://optimizer:8000` | Yes (if certs exist) |
| `dev` | — | — | Uses digital twins for market data | — |
