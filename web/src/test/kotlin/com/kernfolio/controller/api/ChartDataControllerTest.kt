package com.kernfolio.controller.api

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.OptimizationMetrics
import com.kernfolio.domain.OptimizationParameters
import com.kernfolio.domain.OptimizationResults
import com.kernfolio.domain.OptimizationRun
import com.kernfolio.domain.FrontierPoint
import com.kernfolio.domain.Position
import com.kernfolio.domain.User
import com.kernfolio.mockUserDetails
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
}
