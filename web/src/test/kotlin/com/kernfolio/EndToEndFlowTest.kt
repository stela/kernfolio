package com.kernfolio

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import com.kernfolio.domain.Instrument
import com.kernfolio.repository.CachedFxRateRepository
import com.kernfolio.repository.CachedPriceRepository
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.InviteCodeRepository
import com.kernfolio.repository.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post as mvcPost
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndToEndFlowTest {

    companion object {
        private val wireMockServer = WireMockServer(options().dynamicPort()).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("optimizer.base-url") { "http://localhost:${wireMockServer.port()}" }
            registry.add("market-data.scheduler.enabled") { "false" }
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMockServer.stop()
        }
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var inviteCodeRepository: InviteCodeRepository
    @Autowired lateinit var cachedPriceRepository: CachedPriceRepository
    @Autowired lateinit var cachedFxRateRepository: CachedFxRateRepository
    @Autowired lateinit var instrumentRepository: InstrumentRepository

    lateinit var mockMvc: MockMvc

    private var adminSession: MockHttpSession? = null
    private var userSession: MockHttpSession? = null
    private var adminInviteCode: String? = null
    private var user2InviteCode: String? = null
    private var portfolioId: UUID? = null
    private var lastPositionId: UUID? = null
    private var resultsUrl: String? = null

    @BeforeAll
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
    }

    // ── Bootstrap ────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `bootstrap creates system user and invite code`() {
        val systemUser = userRepository.findByUsername("__system__")
        assertThat(systemUser).isNotNull
        assertThat(systemUser!!.enabled).isFalse()
        assertThat(systemUser.role).isEqualTo("ADMIN")

        val validCodes = inviteCodeRepository.findAllValid()
        assertThat(validCodes).hasSize(1)
        adminInviteCode = validCodes.first().code
    }

    // ── Admin registration & login ───────────────────────────────────

    @Test
    @Order(2)
    fun `register first admin user`() {
        mockMvc.perform(get("/register"))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Create an account")))

        mockMvc.perform(
            mvcPost("/register")
                .with(csrf())
                .param("username", "admin1")
                .param("email", "admin1@e2e.test")
                .param("password", "Str0ngP@ss!")
                .param("confirmPassword", "Str0ngP@ss!")
                .param("inviteCode", adminInviteCode!!)
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login?registered"))

        val admin = userRepository.findByUsername("admin1")
        assertThat(admin).isNotNull
        assertThat(admin!!.role).isEqualTo("ADMIN")

        val redeemed = inviteCodeRepository.findByCode(adminInviteCode!!)
        assertThat(redeemed?.usedBy).isNotNull()
    }

    @Test
    @Order(3)
    fun `login as admin`() {
        val result = mockMvc.perform(
            mvcPost("/login")
                .with(csrf())
                .param("username", "admin1")
                .param("password", "Str0ngP@ss!")
        )
            .andExpect(status().is3xxRedirection)
            .andReturn()

        adminSession = result.request.getSession(false) as MockHttpSession
        assertThat(adminSession).isNotNull()
    }

    @Test
    @Order(4)
    fun `admin dashboard is accessible`() {
        mockMvc.perform(get("/dashboard").session(adminSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Your Portfolios")))
    }

    // ── Admin invites second user ────────────────────────────────────

    @Test
    @Order(5)
    fun `admin generates invite code for second user`() {
        val result = mockMvc.perform(
            mvcPost("/admin/users/invite")
                .session(adminSession!!)
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/admin/users"))
            .andReturn()

        user2InviteCode = result.flashMap["generatedCode"] as String
        assertThat(user2InviteCode).isNotBlank()
    }

    @Test
    @Order(6)
    fun `admin pages are accessible`() {
        mockMvc.perform(get("/admin/users").session(adminSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("User Management")))

        mockMvc.perform(get("/admin/flags").session(adminSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Feature Flags")))
    }

    // ── Second user registration & login ─────────────────────────────

    @Test
    @Order(7)
    fun `register non-admin user`() {
        mockMvc.perform(
            mvcPost("/register")
                .with(csrf())
                .param("username", "user2")
                .param("email", "user2@e2e.test")
                .param("password", "Us3rP@ss123")
                .param("confirmPassword", "Us3rP@ss123")
                .param("inviteCode", user2InviteCode!!)
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login?registered"))

        val user = userRepository.findByUsername("user2")
        assertThat(user).isNotNull
        assertThat(user!!.role).isEqualTo("USER")
    }

    @Test
    @Order(8)
    fun `login as non-admin user`() {
        val result = mockMvc.perform(
            mvcPost("/login")
                .with(csrf())
                .param("username", "user2")
                .param("password", "Us3rP@ss123")
        )
            .andExpect(status().is3xxRedirection)
            .andReturn()

        userSession = result.request.getSession(false) as MockHttpSession
        assertThat(userSession).isNotNull()
    }

    @Test
    @Order(9)
    fun `non-admin cannot access admin pages`() {
        mockMvc.perform(get("/admin").session(userSession!!))
            .andExpect(status().isForbidden)
    }

    // ── Portfolio CRUD ───────────────────────────────────────────────

    @Test
    @Order(10)
    fun `create portfolio`() {
        val result = mockMvc.perform(
            mvcPost("/portfolios")
                .session(userSession!!)
                .with(csrf())
                .param("name", "E2E Test Portfolio")
                .param("description", "Integration test portfolio")
                .param("baseCurrency", "EUR")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrlPattern("/portfolios/*"))
            .andReturn()

        val redirectUrl = result.response.redirectedUrl!!
        portfolioId = UUID.fromString(redirectUrl.substringAfterLast("/portfolios/"))
    }

    @Test
    @Order(11)
    fun `view portfolio detail`() {
        mockMvc.perform(get("/portfolios/$portfolioId").session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("E2E Test Portfolio")))
    }

    @Test
    @Order(12)
    fun `edit portfolio`() {
        mockMvc.perform(get("/portfolios/$portfolioId/edit").session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Edit Portfolio")))

        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId")
                .session(userSession!!)
                .with(csrf())
                .param("name", "E2E Portfolio Updated")
                .param("baseCurrency", "EUR")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/portfolios/$portfolioId"))

        mockMvc.perform(get("/portfolios/$portfolioId").session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("E2E Portfolio Updated")))
    }

    // ── Position CRUD ────────────────────────────────────────────────

    @Test
    @Order(13)
    fun `add equity positions`() {
        data class TestPosition(
            val ticker: String, val currency: String,
            val sector: String, val weight: String,
        )

        val positions = listOf(
            TestPosition("GOOG", "USD", "Technology", "0.40"),
            TestPosition("CSU.TO", "CAD", "Technology", "0.35"),
            TestPosition("8PSB", "GBP", "Commodities", "0.25"),
        )

        for (pos in positions) {
            val result = mockMvc.perform(
                mvcPost("/portfolios/$portfolioId/positions")
                    .session(userSession!!)
                    .with(csrf())
                    .param("positionType", "EQUITY")
                    .param("ticker", pos.ticker)
                    .param("currency", pos.currency)
                    .param("weightPct", pos.weight)
                    .param("sector", pos.sector)
                    .param("intrinsicValueLocal", "200")
                    .param("confidence", "0.80")
            )
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString(pos.ticker)))
                .andReturn()

            // Extract position ID from the delete button's data attribute
            val html = result.response.contentAsString
            val match = Regex("""data-delete-position="[^"]*positions/([^"]+)"""").find(html)
            if (match != null) {
                lastPositionId = UUID.fromString(match.groupValues[1])
            }
        }

        // Verify all 3 positions visible on detail page
        mockMvc.perform(get("/portfolios/$portfolioId").session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("GOOG")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("CSU.TO")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("8PSB")))
    }

    @Test
    @Order(14)
    fun `delete and re-add position`() {
        assertThat(lastPositionId).isNotNull()

        // Delete the last position (8PSB)
        mockMvc.perform(
            delete("/portfolios/$portfolioId/positions/$lastPositionId")
                .session(userSession!!)
                .with(csrf())
        )
            .andExpect(status().isOk)

        // Verify it's gone from detail page
        mockMvc.perform(get("/portfolios/$portfolioId").session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("8PSB"))))

        // Re-add the position
        val result = mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/positions")
                .session(userSession!!)
                .with(csrf())
                .param("positionType", "EQUITY")
                .param("ticker", "8PSB")
                .param("name", "Physical Silver ETC")
                .param("currency", "GBP")
                .param("weightPct", "0.25")
                .param("sector", "Commodities")
                .param("intrinsicValueLocal", "200")
                .param("confidence", "0.80")
        )
            .andExpect(status().isOk)
            .andReturn()

        val html = result.response.contentAsString
        val match = Regex("""data-delete-position="[^"]*positions/([^"]+)"""").find(html)
        if (match != null) {
            lastPositionId = UUID.fromString(match.groupValues[1])
        }
    }

    // ── Market data seeding & optimization ───────────────────────────

    @Test
    @Order(15)
    fun `seed market data for optimization`() {
        // Insert instruments with market cap
        instrumentRepository.save(Instrument("GOOG", "Alphabet Inc.", "NASDAQ", "USD", "Technology", BigDecimal("2000000000000")))
        instrumentRepository.save(Instrument("CSU.TO", "Constellation Software", "TSX", "CAD", "Technology", BigDecimal("70000000000")))
        instrumentRepository.save(Instrument("8PSB", "Physical Silver ETC", "LSE", "GBP", "Commodities", BigDecimal("500000000")))

        // OptimizerService.ensureCoverage backfills missing FX history
        // through Frankfurter before running conversion. The seeded FX
        // rates below only cover ~10 days; the 5-year lookback triggers
        // an old-side gap fetch. Stub an empty response so the optimizer
        // happily uses whatever's in the cache.
        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-fx-rates"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody("""{"rates": {}}""")
                )
        )

        // Insert 10 days of prices (within 5-year lookback)
        val dates = (1L..10L).map { LocalDate.now().minusDays(it) }
        for (date in dates) {
            cachedPriceRepository.upsert("GOOG", date, BigDecimal("175.00"), "USD")
            cachedPriceRepository.upsert("CSU.TO", date, BigDecimal("4200.00"), "CAD")
            cachedPriceRepository.upsert("8PSB", date, BigDecimal("45.00"), "GBP")

            cachedFxRateRepository.upsert("EURUSD", date, BigDecimal("1.08500000"))
            cachedFxRateRepository.upsert("EURCAD", date, BigDecimal("1.47000000"))
            cachedFxRateRepository.upsert("EURGBP", date, BigDecimal("0.86000000"))
        }
    }

    @Test
    @Order(16)
    fun `run optimization`() {
        val optimizeResponseJson = javaClass.classLoader
            .getResourceAsStream("fixtures/wiremock/__files/optimize-response.json")!!
            .bufferedReader().readText()

        wireMockServer.stubFor(
            post(urlPathEqualTo("/optimize"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .withBody(optimizeResponseJson)
                )
        )

        // GET optimize form
        mockMvc.perform(get("/portfolios/$portfolioId/optimize").session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Optimize Portfolio")))

        // POST optimization
        val result = mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/optimize")
                .session(userSession!!)
                .with(csrf())
                .param("algorithm", "black_litterman")
                .param("covarianceMethod", "ledoit_wolf")
                .param("riskFreeRate", "0.03")
                .param("tau", "0.05")
                .param("kellyFraction", "0.5")
                .param("minWeight", "0.0")
                .param("maxWeight", "0.40")
                .param("longOnly", "true")
        )
            .andExpect(status().is3xxRedirection)
            .andReturn()

        resultsUrl = result.response.redirectedUrl!!
        assertThat(resultsUrl).contains("/results/")

        wireMockServer.verify(postRequestedFor(urlPathEqualTo("/optimize")))
    }

    @Test
    @Order(17)
    fun `view optimization results and apply weights`() {
        mockMvc.perform(get(resultsUrl!!).session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Optimization Results")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("black_litterman")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Apply to portfolio")))

        // Apply the optimizer's suggestion back to the portfolio — same
        // flow apply-weights.js drives in the browser: pull allocation-data
        // + entry-data, POST a position-id-keyed weights map to /rebalance.
        val runId = resultsUrl!!.substringAfterLast("/results/")

        val allocationJson = mockMvc.perform(
            get("/api/portfolios/$portfolioId/runs/$runId/allocation-data").session(userSession!!)
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        val entryJson = mockMvc.perform(
            get("/api/portfolios/$portfolioId/entry-data").session(userSession!!)
        )
            .andExpect(status().isOk)
            .andReturn().response.contentAsString

        // Jackson 3.x JsonNode has its own map/filter methods that shadow
        // the Kotlin extensions (see FixtureDeserializationTest). Iterate
        // via buildList to avoid the trap.
        val mapper = tools.jackson.databind.json.JsonMapper.builder().build()
        val allocation = mapper.readTree(allocationJson)
        val entry = mapper.readTree(entryJson)

        val labels = buildList<String> { allocation["labels"].forEach { add(it.stringValue()) } }
        val optimized = buildList<Double> { allocation["optimizedWeights"].forEach { add(it.asDouble()) } }
        val optimizedByTicker: Map<String, Double> = labels.zip(optimized).toMap()

        val weights = mutableMapOf<String, String>()
        entry["positions"].forEach { pos ->
            if (pos["positionType"].stringValue() != "EQUITY") return@forEach
            val target = optimizedByTicker[pos["ticker"].stringValue()] ?: return@forEach
            weights[pos["id"].stringValue()] = String.format(java.util.Locale.ROOT, "%.6f", target)
        }
        assertThat(weights).isNotEmpty()

        val body = mapper.writeValueAsString(mapOf("weights" to weights))

        mockMvc.perform(
            mvcPost("/api/portfolios/$portfolioId/rebalance")
                .session(userSession!!)
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
        )
            .andExpect(status().isNoContent)

        // Every equity position's stored weight now matches the optimizer
        // output. Fixture assigns ~0.030303 to each of the 33 tickers;
        // all three positions in this portfolio are in that set.
        val refreshedJson = mockMvc.perform(
            get("/api/portfolios/$portfolioId/entry-data").session(userSession!!)
        ).andReturn().response.contentAsString
        val refreshed = mapper.readTree(refreshedJson)
        refreshed["positions"].forEach { pos ->
            if (pos["positionType"].stringValue() != "EQUITY") return@forEach
            val expected = optimizedByTicker[pos["ticker"].stringValue()] ?: return@forEach
            assertThat(pos["weightPct"].asDouble())
                .isEqualTo(expected, org.assertj.core.data.Offset.offset(1e-5))
        }
    }

    // ── Security & negative tests ───────────────────────────────────

    @Test
    @Order(18)
    fun `admin cannot see other user portfolio`() {
        mockMvc.perform(get("/portfolios/$portfolioId").session(adminSession!!))
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(19)
    fun `admin cannot edit other user portfolio`() {
        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId")
                .session(adminSession!!)
                .with(csrf())
                .param("name", "Hijacked")
                .param("baseCurrency", "USD")
        )
            .andExpect(status().isNotFound)

        // Verify name unchanged
        mockMvc.perform(get("/portfolios/$portfolioId").session(userSession!!))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("E2E Portfolio Updated")))
    }

    @Test
    @Order(20)
    fun `admin cannot delete other user portfolio`() {
        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/delete")
                .session(adminSession!!)
                .with(csrf())
        )
            .andExpect(status().isNotFound)

        // Verify portfolio still exists
        mockMvc.perform(get("/portfolios/$portfolioId").session(userSession!!))
            .andExpect(status().isOk)
    }

    @Test
    @Order(21)
    fun `admin cannot add position to other user portfolio`() {
        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/positions")
                .session(adminSession!!)
                .with(csrf())
                .param("positionType", "EQUITY")
                .param("ticker", "AAPL")
                .param("currency", "USD")
                .param("weightPct", "0.10")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(22)
    fun `admin cannot delete other user position`() {
        mockMvc.perform(
            delete("/portfolios/$portfolioId/positions/$lastPositionId")
                .session(adminSession!!)
                .with(csrf())
        )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(23)
    fun `admin cannot optimize other user portfolio`() {
        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/optimize")
                .session(adminSession!!)
                .with(csrf())
                .param("algorithm", "black_litterman")
        )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(24)
    fun `admin cannot view other user results`() {
        val runId = resultsUrl!!.substringAfterLast("/results/")
        mockMvc.perform(get("/portfolios/$portfolioId/results/$runId").session(adminSession!!))
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(25)
    fun `unauthenticated requests redirect to login`() {
        mockMvc.perform(get("/portfolios/$portfolioId"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login"))

        mockMvc.perform(get("/dashboard"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login"))

        mockMvc.perform(get("/portfolios/$portfolioId/optimize"))
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login"))
    }

    @Test
    @Order(26)
    fun `unauthenticated API requests return 401`() {
        mockMvc.perform(get("/api/portfolios/$portfolioId/entry-data"))
            .andExpect(status().isUnauthorized)

        val runId = resultsUrl!!.substringAfterLast("/results/")
        mockMvc.perform(get("/api/portfolios/$portfolioId/runs/$runId/allocation-data"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    @Order(27)
    fun `CSRF-less POST requests are rejected`() {
        // CSRF-blocked HTML form POSTs redirect back to the form with
        // ?sessionExpired=1 (see FormAccessDeniedHandler), so the page
        // can show an inline banner instead of a raw 403 error.
        mockMvc.perform(
            mvcPost("/portfolios")
                .session(userSession!!)
                .param("name", "No CSRF")
                .param("baseCurrency", "EUR")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("sessionExpired=1")))

        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/positions")
                .session(userSession!!)
                .param("positionType", "EQUITY")
                .param("ticker", "AAPL")
                .param("currency", "USD")
                .param("weightPct", "0.10")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("sessionExpired=1")))

        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/optimize")
                .session(userSession!!)
                .param("algorithm", "black_litterman")
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("sessionExpired=1")))

        mockMvc.perform(
            delete("/portfolios/$portfolioId/positions/$lastPositionId")
                .session(userSession!!)
        )
            .andExpect(status().isForbidden)

        mockMvc.perform(
            mvcPost("/admin/users/invite")
                .session(adminSession!!)
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("sessionExpired=1")))
    }

    @Test
    @Order(28)
    fun `nonexistent portfolio returns 404`() {
        val fakeId = UUID.randomUUID()

        mockMvc.perform(get("/portfolios/$fakeId").session(userSession!!))
            .andExpect(status().isNotFound)

        mockMvc.perform(
            mvcPost("/portfolios/$fakeId")
                .session(userSession!!)
                .with(csrf())
                .param("name", "Ghost")
                .param("baseCurrency", "EUR")
        )
            .andExpect(status().isNotFound)

        mockMvc.perform(
            mvcPost("/portfolios/$fakeId/delete")
                .session(userSession!!)
                .with(csrf())
        )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(29)
    fun `nonexistent position returns 404`() {
        val fakePos = UUID.randomUUID()
        mockMvc.perform(
            delete("/portfolios/$portfolioId/positions/$fakePos")
                .session(userSession!!)
                .with(csrf())
        )
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(30)
    fun `nonexistent optimization run returns 404`() {
        val fakeRun = UUID.randomUUID()
        mockMvc.perform(get("/portfolios/$portfolioId/results/$fakeRun").session(userSession!!))
            .andExpect(status().isNotFound)
    }

    @Test
    @Order(31)
    fun `portfolio validation rejects blank name`() {
        mockMvc.perform(
            mvcPost("/portfolios")
                .session(userSession!!)
                .with(csrf())
                .param("name", "")
                .param("baseCurrency", "EUR")
        )
            .andExpect(status().isOk) // re-renders form with errors
            .andExpect(content().string(org.hamcrest.Matchers.containsString("required")))
    }

    @Test
    @Order(32)
    fun `registration rejects duplicate username`() {
        mockMvc.perform(
            mvcPost("/register")
                .with(csrf())
                .param("username", "admin1") // already exists
                .param("email", "fresh@e2e.test")
                .param("password", "ValidP@ss1")
                .param("confirmPassword", "ValidP@ss1")
                .param("inviteCode", "FAKECODE")
        )
            .andExpect(status().isOk) // re-renders form
            .andExpect(content().string(org.hamcrest.Matchers.containsString("could not be completed")))
    }

    @Test
    @Order(33)
    fun `registration rejects password mismatch`() {
        mockMvc.perform(
            mvcPost("/register")
                .with(csrf())
                .param("username", "newuser")
                .param("email", "new@e2e.test")
                .param("password", "ValidP@ss1")
                .param("confirmPassword", "DifferentP@ss")
                .param("inviteCode", "FAKECODE")
        )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("do not match")))
    }

    @Test
    @Order(34)
    fun `non-admin cannot access admin endpoints`() {
        mockMvc.perform(get("/admin").session(userSession!!))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/users").session(userSession!!))
            .andExpect(status().isForbidden)
        mockMvc.perform(get("/admin/flags").session(userSession!!))
            .andExpect(status().isForbidden)
        mockMvc.perform(
            mvcPost("/admin/users/invite").session(userSession!!).with(csrf())
        )
            .andExpect(status().isForbidden)
    }

    // ── Privilege escalation & injection attempts ──────────────────

    @Test
    @Order(35)
    fun `registration with extra role param does not grant admin`() {
        // Generate a fresh invite code for this test
        val inviteResult = mockMvc.perform(
            mvcPost("/admin/users/invite").session(adminSession!!).with(csrf())
        ).andReturn()
        val freshCode = inviteResult.flashMap["generatedCode"] as String

        mockMvc.perform(
            mvcPost("/register")
                .with(csrf())
                .param("username", "sneaky")
                .param("email", "sneaky@e2e.test")
                .param("password", "Sn3akyP@ss!")
                .param("confirmPassword", "Sn3akyP@ss!")
                .param("inviteCode", freshCode)
                .param("role", "ADMIN")          // extra field — should be ignored
                .param("enabled", "true")         // extra field — should be ignored
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login?registered"))

        val sneaky = userRepository.findByUsername("sneaky")
        assertThat(sneaky).isNotNull
        assertThat(sneaky!!.role).isEqualTo("USER")  // NOT admin
    }

    @Test
    @Order(36)
    fun `registration with existing admin username is rejected`() {
        mockMvc.perform(
            mvcPost("/register")
                .with(csrf())
                .param("username", "admin1")       // taken
                .param("email", "unique@e2e.test")
                .param("password", "ValidP@ss1")
                .param("confirmPassword", "ValidP@ss1")
                .param("inviteCode", "ANYCODE")
        )
            .andExpect(status().isOk) // re-renders form
            .andExpect(content().string(org.hamcrest.Matchers.containsString("could not be completed")))
    }

    @Test
    @Order(37)
    fun `reused invite code is rejected`() {
        mockMvc.perform(
            mvcPost("/register")
                .with(csrf())
                .param("username", "reuser")
                .param("email", "reuser@e2e.test")
                .param("password", "ValidP@ss1")
                .param("confirmPassword", "ValidP@ss1")
                .param("inviteCode", adminInviteCode!!)  // already redeemed in test 2
        )
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid or expired")))
    }

    @Test
    @Order(38)
    fun `portfolio creation with injected userId param still assigns to authenticated user`() {
        val adminId = userRepository.findByUsername("admin1")!!.id!!

        val result = mockMvc.perform(
            mvcPost("/portfolios")
                .session(userSession!!)
                .with(csrf())
                .param("name", "Injection Test")
                .param("baseCurrency", "EUR")
                .param("userId", adminId.toString())  // try to hijack ownership
        )
            .andExpect(status().is3xxRedirection)
            .andReturn()

        val injectedPortfolioId = UUID.fromString(
            result.response.redirectedUrl!!.substringAfterLast("/portfolios/")
        )

        // Admin should NOT see this portfolio (it should belong to user2)
        mockMvc.perform(get("/portfolios/$injectedPortfolioId").session(adminSession!!))
            .andExpect(status().isNotFound)

        // User2 should see it
        mockMvc.perform(get("/portfolios/$injectedPortfolioId").session(userSession!!))
            .andExpect(status().isOk)

        // Clean up
        mockMvc.perform(
            mvcPost("/portfolios/$injectedPortfolioId/delete")
                .session(userSession!!)
                .with(csrf())
        ).andExpect(status().is3xxRedirection)
    }

    @Test
    @Order(39)
    fun `position creation with injected portfolioId param does not cross portfolios`() {
        // Create a portfolio as admin
        val adminPortfolioResult = mockMvc.perform(
            mvcPost("/portfolios")
                .session(adminSession!!)
                .with(csrf())
                .param("name", "Admin Portfolio")
                .param("baseCurrency", "EUR")
        ).andReturn()
        val adminPortfolioId = UUID.fromString(
            adminPortfolioResult.response.redirectedUrl!!.substringAfterLast("/portfolios/")
        )

        // User2 tries to add position to user2's portfolio but injects admin's portfolioId
        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/positions")
                .session(userSession!!)
                .with(csrf())
                .param("positionType", "EQUITY")
                .param("ticker", "INJECTED")
                .param("currency", "USD")
                .param("weightPct", "0.10")
                .param("portfolioId", adminPortfolioId.toString())  // injection attempt
        )
            .andExpect(status().isOk) // position added to URL-path portfolio, not injected one

        // Admin's portfolio should NOT have the injected position
        mockMvc.perform(get("/portfolios/$adminPortfolioId").session(adminSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("INJECTED"))))

        // Clean up: delete the injected position and admin portfolio
        val detailHtml = mockMvc.perform(get("/portfolios/$portfolioId").session(userSession!!))
            .andReturn().response.contentAsString
        val posMatch = Regex("""data-delete-position="[^"]*positions/([^"]+)"""").findAll(detailHtml)
        for (m in posMatch) {
            val posId = m.groupValues[1]
            // Delete the INJECTED position if found
            val posDetailHtml = detailHtml
            if (posDetailHtml.contains("INJECTED")) {
                mockMvc.perform(
                    delete("/portfolios/$portfolioId/positions/$posId")
                        .session(userSession!!).with(csrf())
                )
            }
        }
        mockMvc.perform(
            mvcPost("/portfolios/$adminPortfolioId/delete")
                .session(adminSession!!).with(csrf())
        )
    }

    @Test
    @Order(40)
    fun `accessing results via wrong portfolio returns 404`() {
        // Create a second portfolio for user2
        val otherResult = mockMvc.perform(
            mvcPost("/portfolios")
                .session(userSession!!)
                .with(csrf())
                .param("name", "Other Portfolio")
                .param("baseCurrency", "EUR")
        ).andReturn()
        val otherPortfolioId = UUID.fromString(
            otherResult.response.redirectedUrl!!.substringAfterLast("/portfolios/")
        )

        // Try to access the optimization run from portfolioId via otherPortfolioId's URL
        val runId = resultsUrl!!.substringAfterLast("/results/")
        mockMvc.perform(get("/portfolios/$otherPortfolioId/results/$runId").session(userSession!!))
            .andExpect(status().isNotFound)

        // Clean up
        mockMvc.perform(
            mvcPost("/portfolios/$otherPortfolioId/delete")
                .session(userSession!!).with(csrf())
        )
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    @Test
    @Order(90)
    fun `delete portfolio`() {
        mockMvc.perform(
            mvcPost("/portfolios/$portfolioId/delete")
                .session(userSession!!)
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/dashboard"))

        // Dashboard shows empty state
        mockMvc.perform(get("/dashboard").session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("No portfolios yet")))
    }

    @Test
    @Order(91)
    fun `logout`() {
        mockMvc.perform(
            mvcPost("/logout")
                .session(userSession!!)
                .with(csrf())
        )
            .andExpect(status().is3xxRedirection)
            .andExpect(redirectedUrl("/login?logout"))
    }
}
