package com.kernfolio.controller.api

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.Position
import com.kernfolio.domain.User
import com.kernfolio.mockUserDetails
import com.kernfolio.repository.PositionRepository
import com.kernfolio.repository.UserRepository
import com.kernfolio.service.PortfolioService
import org.hamcrest.Matchers.hasSize
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
class PortfolioApiControllerTest {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var portfolioService: PortfolioService
    @Autowired lateinit var positionRepository: PositionRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
    }

    private fun createUser(username: String): User = userRepository.save(
        User(username = username, email = "$username@test.com",
             passwordHash = passwordEncoder.encode("pass")!!, role = "USER")
    )

    @Test
    fun `entry-data returns baseCurrency and positions`() {
        val user = createUser("alice")
        val portfolio = portfolioService.create(user.id!!, "Test", null, "USD")
        val portfolioId = portfolio.id!!

        positionRepository.save(
            Position(portfolioId = portfolioId, ticker = "GOOG", currency = "USD",
                     weightPct = BigDecimal("0.060000"), sector = "Technology")
        )
        positionRepository.save(
            Position(portfolioId = portfolioId, ticker = "CASH.EUR", currency = "EUR",
                     weightPct = BigDecimal("0.020000"), positionType = "CASH")
        )

        mockMvc.perform(
            get("/api/portfolios/$portfolioId/entry-data")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.baseCurrency").value("USD"))
            .andExpect(jsonPath("$.positions", hasSize<Any>(2)))
            .andExpect(jsonPath("$.positions[0].ticker").value("GOOG"))
            .andExpect(jsonPath("$.positions[0].positionType").value("EQUITY"))
            .andExpect(jsonPath("$.positions[1].positionType").value("CASH"))
    }

    @Test
    fun `entry-data returns 404 for other user portfolio`() {
        val alice = createUser("alice")
        val bob = createUser("bob")
        val portfolio = portfolioService.create(alice.id!!, "Alice Portfolio", null, "EUR")

        mockMvc.perform(
            get("/api/portfolios/${portfolio.id}/entry-data")
                .with(mockUserDetails(bob))
        )
            .andExpect(status().isNotFound)
    }

    @Test
    fun `entry-data returns 401 without authentication`() {
        mockMvc.perform(
            get("/api/portfolios/${UUID.randomUUID()}/entry-data")
        )
            .andExpect(status().isUnauthorized)
    }
}
