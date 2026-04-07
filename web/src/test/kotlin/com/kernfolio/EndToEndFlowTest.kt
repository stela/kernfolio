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
            val ticker: String, val name: String, val currency: String,
            val sector: String, val weight: String,
        )

        val positions = listOf(
            TestPosition("GOOG", "Alphabet Inc.", "USD", "Technology", "0.40"),
            TestPosition("CSU.TO", "Constellation Software", "CAD", "Technology", "0.35"),
            TestPosition("8PSB", "Physical Silver ETC", "GBP", "Commodities", "0.25"),
        )

        for (pos in positions) {
            val result = mockMvc.perform(
                mvcPost("/portfolios/$portfolioId/positions")
                    .session(userSession!!)
                    .with(csrf())
                    .param("positionType", "EQUITY")
                    .param("ticker", pos.ticker)
                    .param("name", pos.name)
                    .param("currency", pos.currency)
                    .param("weightPct", pos.weight)
                    .param("sector", pos.sector)
                    .param("intrinsicValueLocal", "200")
                    .param("confidencePct", "0.80")
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
                .param("confidencePct", "0.80")
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
    fun `view optimization results`() {
        mockMvc.perform(get(resultsUrl!!).session(userSession!!))
            .andExpect(status().isOk)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("Optimization Results")))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("black_litterman")))
    }

    // ── Tenant isolation ─────────────────────────────────────────────

    @Test
    @Order(18)
    fun `admin cannot see other user portfolio`() {
        mockMvc.perform(get("/portfolios/$portfolioId").session(adminSession!!))
            .andExpect(status().isNotFound)
    }

    // ── Cleanup ──────────────────────────────────────────────────────

    @Test
    @Order(19)
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
    @Order(20)
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
