package com.kernfolio.controller.api

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.OptimizationMetrics
import com.kernfolio.domain.OptimizationParameters
import com.kernfolio.domain.OptimizationResults
import com.kernfolio.domain.OptimizationRun
import com.kernfolio.domain.FrontierPoint
import com.kernfolio.domain.Instrument
import com.kernfolio.domain.Position
import com.kernfolio.domain.User
import com.kernfolio.mockUserDetails
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.OptimizationRunRepository
import com.kernfolio.repository.PositionRepository
import com.kernfolio.repository.UserRepository
import com.kernfolio.service.PortfolioService
import org.hamcrest.Matchers.hasSize
import org.hamcrest.Matchers.closeTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class ChartDataControllerTest {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var portfolioService: PortfolioService
    @Autowired lateinit var positionRepository: PositionRepository
    @Autowired lateinit var optimizationRunRepository: OptimizationRunRepository
    @Autowired lateinit var instrumentRepository: InstrumentRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
    }

    private fun createUser(username: String): User = userRepository.save(
        User(
            username = username,
            email = "$username@test.com",
            passwordHash = passwordEncoder.encode("testpassword")!!,
            role = "USER",
        )
    )

    @Test
    fun `allocation-data returns correct JSON structure`() {
        val user = createUser("alice")
        val portfolio = portfolioService.create(user.id!!, "Test", null, "EUR")
        val portfolioId = portfolio.id!!

        positionRepository.save(
            Position(
                portfolioId = portfolioId,
                ticker = "GOOG",
                currency = "USD",
                weightPct = BigDecimal("0.060000"),
                sector = "Technology",
            )
        )
        positionRepository.save(
            Position(
                portfolioId = portfolioId,
                ticker = "AMZN",
                currency = "USD",
                weightPct = BigDecimal("0.040000"),
                sector = "Technology",
            )
        )

        val run = optimizationRunRepository.save(
            OptimizationRun(
                portfolioId = portfolioId,
                algorithm = "black_litterman",
                parameters = OptimizationParameters(),
                results = OptimizationResults(
                    optimizedWeights = mapOf("GOOG" to 0.08, "AMZN" to 0.05, "NVDA" to 0.03),
                    metrics = OptimizationMetrics(
                        expectedAnnualReturn = 0.09,
                        annualVolatility = 0.17,
                        sharpeRatio = 0.35,
                        cvar95 = -0.03,
                    ),
                ),
            )
        )

        mockMvc.perform(
            get("/api/portfolios/${portfolio.id}/runs/${run.id}/allocation-data")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.labels", hasSize<Any>(3)))
            .andExpect(jsonPath("$.optimizedWeights", hasSize<Any>(3)))
            .andExpect(jsonPath("$.currentWeights", hasSize<Any>(3)))
    }

    // ── Cash share of Kelly-sized runs ───────────────────────────────

    private fun portfolioWith(user: User, vararg positions: Triple<String, String, String>): UUID {
        val portfolioId = portfolioService.create(user.id!!, "Test", null, "EUR").id!!
        positions.forEach { (ticker, type, weight) ->
            positionRepository.save(
                Position(
                    portfolioId = portfolioId,
                    positionType = type,
                    ticker = ticker,
                    currency = ticker.substringAfter("CASH.", "USD"),
                    weightPct = BigDecimal(weight),
                )
            )
        }
        return portfolioId
    }

    /** JsonPath hands back Double or BigDecimal depending on the digits; compare as numbers. */
    private fun near(expected: Double) = object : org.hamcrest.TypeSafeMatcher<Number>() {
        override fun matchesSafely(actual: Number) = kotlin.math.abs(actual.toDouble() - expected) < 1e-9
        override fun describeTo(description: org.hamcrest.Description) { description.appendText("a number near $expected") }
    }

    private fun runWith(portfolioId: UUID, cashWeight: Double?, viewConfidences: Map<String, Double>? = null) =
        optimizationRunRepository.save(
            OptimizationRun(
                portfolioId = portfolioId,
                algorithm = "black_litterman",
                parameters = OptimizationParameters(),
                results = OptimizationResults(
                    optimizedWeights = mapOf("GOOG" to 0.45, "AMZN" to 0.25),
                    cashWeight = cashWeight,
                    viewConfidences = viewConfidences,
                ),
            )
        )

    @Test
    fun `allocation-data splits the cash share over cash positions in their current proportions`() {
        val user = createUser("alice")
        val portfolioId = portfolioWith(
            user,
            Triple("GOOG", "EQUITY", "0.500000"), Triple("AMZN", "EQUITY", "0.300000"),
            Triple("CASH.EUR", "CASH", "0.150000"), Triple("CASH.USD", "CASH", "0.050000"),
        )
        val run = runWith(portfolioId, cashWeight = 0.30, viewConfidences = mapOf("GOOG" to 0.62))

        mockMvc.perform(get("/api/portfolios/$portfolioId/runs/${run.id}/allocation-data").with(mockUserDetails(user)))
            .andExpect(status().isOk)
            // sorted: AMZN, CASH.EUR, CASH.USD, GOOG — 0.30 cash split 3:1
            .andExpect(jsonPath("$.labels", org.hamcrest.Matchers.contains("AMZN", "CASH.EUR", "CASH.USD", "GOOG")))
            .andExpect(jsonPath("$.optimizedWeights[0]").value(0.25))
            .andExpect(jsonPath("$.optimizedWeights[1]", near(0.225)))
            .andExpect(jsonPath("$.optimizedWeights[2]", near(0.075)))
            .andExpect(jsonPath("$.optimizedWeights[3]").value(0.45))
            .andExpect(jsonPath("$.cashTargets").value(true))
            .andExpect(jsonPath("$.unallocatedCash").value(0.0))
            .andExpect(jsonPath("$.viewConfidences.GOOG").value(0.62))
    }

    @Test
    fun `allocation-data splits cash evenly when the cash positions are currently empty`() {
        val user = createUser("alice")
        val portfolioId = portfolioWith(
            user,
            Triple("GOOG", "EQUITY", "0.600000"), Triple("AMZN", "EQUITY", "0.400000"),
            Triple("CASH.EUR", "CASH", "0.000000"), Triple("CASH.USD", "CASH", "0.000000"),
        )
        val run = runWith(portfolioId, cashWeight = 0.30)

        mockMvc.perform(get("/api/portfolios/$portfolioId/runs/${run.id}/allocation-data").with(mockUserDetails(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.optimizedWeights[1]", near(0.15)))
            .andExpect(jsonPath("$.optimizedWeights[2]", near(0.15)))
    }

    @Test
    fun `allocation-data reports cash it has no position for as unallocated`() {
        val user = createUser("alice")
        val portfolioId = portfolioWith(user, Triple("GOOG", "EQUITY", "0.600000"), Triple("AMZN", "EQUITY", "0.400000"))
        val run = runWith(portfolioId, cashWeight = 0.30)

        mockMvc.perform(get("/api/portfolios/$portfolioId/runs/${run.id}/allocation-data").with(mockUserDetails(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.labels", org.hamcrest.Matchers.contains("AMZN", "GOOG", "Cash (unallocated)")))
            .andExpect(jsonPath("$.optimizedWeights[2]").value(0.30))
            .andExpect(jsonPath("$.currentWeights[2]").value(0.0))
            .andExpect(jsonPath("$.unallocatedCash").value(0.30))
    }

    @Test
    fun `runs stored before Kelly sizing have no cash targets and leave cash alone`() {
        val user = createUser("alice")
        val portfolioId = portfolioWith(
            user, Triple("GOOG", "EQUITY", "0.500000"), Triple("CASH.EUR", "CASH", "0.500000"),
        )
        val run = runWith(portfolioId, cashWeight = null)

        mockMvc.perform(get("/api/portfolios/$portfolioId/runs/${run.id}/allocation-data").with(mockUserDetails(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cashTargets").value(false))
            .andExpect(jsonPath("$.unallocatedCash").value(0.0))

        mockMvc.perform(get("/api/portfolios/$portfolioId/runs/${run.id}/summary-data").with(mockUserDetails(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cashWeight").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `summary-data carries the cash weight`() {
        val user = createUser("alice")
        val portfolioId = portfolioWith(user, Triple("GOOG", "EQUITY", "1.000000"))
        val run = runWith(portfolioId, cashWeight = 0.205)

        mockMvc.perform(get("/api/portfolios/$portfolioId/runs/${run.id}/summary-data").with(mockUserDetails(user)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.cashWeight").value(0.205))
    }

    @Test
    fun `frontier-data returns frontier points and optimized point`() {
        val user = createUser("bob")
        val portfolio = portfolioService.create(user.id!!, "Test", null, "EUR")

        val run = optimizationRunRepository.save(
            OptimizationRun(
                portfolioId = portfolio.id!!,
                algorithm = "black_litterman",
                parameters = OptimizationParameters(),
                results = OptimizationResults(
                    optimizedWeights = mapOf("GOOG" to 0.5),
                    metrics = OptimizationMetrics(
                        expectedAnnualReturn = 0.09,
                        annualVolatility = 0.17,
                        sharpeRatio = 0.35,
                        cvar95 = -0.03,
                    ),
                    efficientFrontier = listOf(
                        FrontierPoint(risk = 0.10, ret = 0.05),
                        FrontierPoint(risk = 0.15, ret = 0.08),
                        FrontierPoint(risk = 0.20, ret = 0.10),
                    ),
                ),
            )
        )

        mockMvc.perform(
            get("/api/portfolios/${portfolio.id}/runs/${run.id}/frontier-data")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.frontier", hasSize<Any>(3)))
            .andExpect(jsonPath("$.frontier[0].risk").value(closeTo(0.10, 0.001)))
            .andExpect(jsonPath("$.frontier[0].ret").value(closeTo(0.05, 0.001)))
            .andExpect(jsonPath("$.optimized.risk").value(closeTo(0.17, 0.001)))
            .andExpect(jsonPath("$.optimized.ret").value(closeTo(0.09, 0.001)))
    }

    @Test
    fun `summary-data returns raw metrics and ISO timestamp for client-side formatting`() {
        val user = createUser("summary")
        val portfolio = portfolioService.create(user.id!!, "Test", null, "EUR")

        val run = optimizationRunRepository.save(
            OptimizationRun(
                portfolioId = portfolio.id!!,
                algorithm = "black_litterman",
                parameters = OptimizationParameters(),
                results = OptimizationResults(
                    optimizedWeights = mapOf("GOOG" to 1.0),
                    metrics = OptimizationMetrics(
                        expectedAnnualReturn = 0.09,
                        annualVolatility = 0.17,
                        sharpeRatio = 0.35,
                        cvar95 = -0.03,
                    ),
                ),
            )
        )

        mockMvc.perform(
            get("/api/portfolios/${portfolio.id}/runs/${run.id}/summary-data")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.expectedAnnualReturn").value(closeTo(0.09, 0.001)))
            .andExpect(jsonPath("$.annualVolatility").value(closeTo(0.17, 0.001)))
            .andExpect(jsonPath("$.sharpeRatio").value(closeTo(0.35, 0.001)))
            .andExpect(jsonPath("$.cvar95").value(closeTo(-0.03, 0.001)))
            .andExpect(jsonPath("$.createdAt").isString)
    }

    @Test
    fun `summary-data returns null metrics when the run has none`() {
        val user = createUser("nometrics")
        val portfolio = portfolioService.create(user.id!!, "Test", null, "EUR")
        val run = optimizationRunRepository.save(
            OptimizationRun(
                portfolioId = portfolio.id!!,
                algorithm = "black_litterman",
                parameters = OptimizationParameters(),
                results = OptimizationResults(optimizedWeights = mapOf("GOOG" to 1.0)),
            )
        )

        mockMvc.perform(
            get("/api/portfolios/${portfolio.id}/runs/${run.id}/summary-data")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sharpeRatio").value(org.hamcrest.Matchers.nullValue()))
    }

    @Test
    fun `summary-data returns 404 for another user's portfolio`() {
        val owner = createUser("owner")
        val other = createUser("intruder")
        val portfolio = portfolioService.create(owner.id!!, "Private", null, "EUR")
        val run = optimizationRunRepository.save(
            OptimizationRun(
                portfolioId = portfolio.id!!,
                algorithm = "black_litterman",
                parameters = OptimizationParameters(),
                results = OptimizationResults(optimizedWeights = mapOf("GOOG" to 1.0)),
            )
        )

        mockMvc.perform(
            get("/api/portfolios/${portfolio.id}/runs/${run.id}/summary-data")
                .with(mockUserDetails(other))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `returns 404 when run does not belong to portfolio`() {
        val user = createUser("charlie")
        val userId = user.id!!
        val portfolio1 = portfolioService.create(userId, "P1", null, "EUR")
        val portfolio2 = portfolioService.create(userId, "P2", null, "EUR")

        val run = optimizationRunRepository.save(
            OptimizationRun(
                portfolioId = portfolio1.id!!,
                algorithm = "black_litterman",
                parameters = OptimizationParameters(),
                results = OptimizationResults(optimizedWeights = mapOf("GOOG" to 1.0)),
            )
        )

        // Try to access run via wrong portfolio
        mockMvc.perform(
            get("/api/portfolios/${portfolio2.id}/runs/${run.id}/allocation-data")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `returns 403 without authentication`() {
        mockMvc.perform(
            get("/api/portfolios/${UUID.randomUUID()}/runs/${UUID.randomUUID()}/allocation-data")
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `discrete-data returns weights, baseCurrency, and fractional flags`() {
        val user = createUser("dave")
        val portfolio = portfolioService.create(user.id!!, "Test", null, "USD")
        val portfolioId = portfolio.id!!

        instrumentRepository.save(Instrument(ticker = "GOOG", currency = "USD", fractional = false))
        instrumentRepository.save(Instrument(ticker = "VFMF", currency = "USD", fractional = true))

        positionRepository.save(
            Position(portfolioId = portfolioId, ticker = "GOOG", currency = "USD", weightPct = BigDecimal("0.06"))
        )
        positionRepository.save(
            Position(portfolioId = portfolioId, ticker = "CASH.USD", currency = "USD",
                     weightPct = BigDecimal("0.02"), positionType = "CASH")
        )

        val run = optimizationRunRepository.save(
            OptimizationRun(
                portfolioId = portfolioId,
                algorithm = "black_litterman",
                parameters = OptimizationParameters(),
                results = OptimizationResults(
                    optimizedWeights = mapOf("GOOG" to 0.06, "VFMF" to 0.04, "CASH.USD" to 0.02),
                ),
            )
        )

        mockMvc.perform(
            get("/api/portfolios/$portfolioId/runs/${run.id}/discrete-data")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.baseCurrency").value("USD"))
            .andExpect(jsonPath("$.weights.GOOG").value(closeTo(0.06, 0.001)))
            .andExpect(jsonPath("$.fractional.GOOG").value(false))
            .andExpect(jsonPath("$.fractional.VFMF").value(true))
            .andExpect(jsonPath("$.fractional['CASH.USD']").value(true))
    }
}
