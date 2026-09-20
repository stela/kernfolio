package com.kernfolio.controller

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.User
import com.kernfolio.mockUserDetails
import com.kernfolio.repository.PortfolioRepository
import com.kernfolio.repository.PositionRepository
import com.kernfolio.repository.UserRepository
import com.kernfolio.service.PortfolioService
import com.kernfolio.service.PositionForm
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class PortfolioControllerTest {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var portfolioRepository: PortfolioRepository
    @Autowired lateinit var positionRepository: PositionRepository
    @Autowired lateinit var portfolioService: PortfolioService
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
    }

    @Nested
    inner class NewPortfolio {

        @Test
        fun `GET portfolios new returns form`() {
            val testUser = createUser("alice")
            mockMvc.perform(get("/portfolios/new").with(mockUserDetails(testUser)))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("New Portfolio")))
        }

        @Test
        fun `POST portfolios creates and redirects`() {
            val testUser = createUser("alice")

            mockMvc.perform(
                post("/portfolios")
                    .with(mockUserDetails(testUser))
                    .with(csrf())
                    .param("name", "Growth Fund")
                    .param("baseCurrency", "USD")
            )
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrlPattern("/portfolios/*"))

            val portfolios = portfolioRepository.findByUserId(testUser.id!!)
            assert(portfolios.size == 1)
            assert(portfolios[0].name == "Growth Fund")
        }

        @Test
        fun `POST portfolios with blank name returns validation error`() {
            val testUser = createUser("alice")
            mockMvc.perform(
                post("/portfolios")
                    .with(mockUserDetails(testUser))
                    .with(csrf())
                    .param("name", "")
                    .param("baseCurrency", "EUR")
            )
                .andExpect(status().isOk)
                .andExpect(model().attributeHasFieldErrors("portfolioForm", "name"))
        }

        @Test
        fun `POST portfolios without CSRF redirects with sessionExpired flag`() {
            val testUser = createUser("alice")
            mockMvc.perform(
                post("/portfolios")
                    .with(mockUserDetails(testUser))
                    .param("name", "Test")
                    .param("baseCurrency", "EUR")
            )
                .andExpect(status().is3xxRedirection)
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("sessionExpired=1")))
        }
    }

    @Nested
    inner class PortfolioDetail {

        @Test
        fun `GET portfolio detail returns 200 with positions`() {
            val testUser = createUser("alice")
            val userId = testUser.id!!
            val portfolio = portfolioService.create(userId, "My Fund", null, "EUR")
            portfolioService.addPosition(portfolio.id!!, userId, PositionForm(
                ticker = "GOOG", currency = "USD", weightPct = BigDecimal("0.15"),
            ))

            mockMvc.perform(
                get("/portfolios/${portfolio.id}").with(mockUserDetails(testUser))
            )
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("My Fund")))
                .andExpect(content().string(containsString("GOOG")))
        }

        @Test
        fun `GET portfolio detail returns 404 for nonexistent portfolio`() {
            val testUser = createUser("alice")
            mockMvc.perform(
                get("/portfolios/${UUID.randomUUID()}").with(mockUserDetails(testUser))
            )
                .andExpect(status().isNotFound)
        }

        @Test
        fun `user cannot see another user portfolio`() {
            val alice = createUser("alice")
            val bob = createUser("bob")
            val portfolio = portfolioService.create(alice.id!!, "Alice Fund", null, "EUR")

            mockMvc.perform(
                get("/portfolios/${portfolio.id}").with(mockUserDetails(bob))
            )
                .andExpect(status().isNotFound)
        }
    }

    @Nested
    inner class EditPortfolio {

        @Test
        fun `GET edit returns form with populated data`() {
            val testUser = createUser("alice")
            val portfolio = portfolioService.create(testUser.id!!, "Original", "desc", "EUR")

            mockMvc.perform(
                get("/portfolios/${portfolio.id}/edit").with(mockUserDetails(testUser))
            )
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("Edit Portfolio")))
                .andExpect(content().string(containsString("Original")))
        }

        @Test
        fun `POST update changes portfolio name`() {
            val testUser = createUser("alice")
            val portfolio = portfolioService.create(testUser.id!!, "Original", null, "EUR")

            mockMvc.perform(
                post("/portfolios/${portfolio.id}")
                    .with(mockUserDetails(testUser))
                    .with(csrf())
                    .param("name", "Updated Name")
                    .param("baseCurrency", "EUR")
            )
                .andExpect(status().is3xxRedirection)

            val updated = portfolioRepository.findById(portfolio.id!!).get()
            assert(updated.name == "Updated Name")
        }
    }

    @Nested
    inner class DeletePortfolio {

        @Test
        fun `POST delete removes portfolio and redirects`() {
            val testUser = createUser("alice")
            val portfolio = portfolioService.create(testUser.id!!, "To Delete", null, "EUR")

            mockMvc.perform(
                post("/portfolios/${portfolio.id}/delete")
                    .with(mockUserDetails(testUser))
                    .with(csrf())
            )
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/dashboard"))

            assert(portfolioRepository.findById(portfolio.id!!).isEmpty)
        }
    }

    @Nested
    inner class HtmxPositionEndpoints {

        @Test
        fun `GET new-row returns HTML fragment without full page`() {
            val testUser = createUser("alice")
            val portfolio = portfolioService.create(testUser.id!!, "Test", null, "EUR")

            val result = mockMvc.perform(
                get("/portfolios/${portfolio.id}/positions/new-row")
                    .with(mockUserDetails(testUser))
            )
                .andExpect(status().isOk)
                .andReturn()

            val body = result.response.contentAsString
            assert(body.contains("<tr")) { "Should contain a table row" }
            assert(!body.contains("<html")) { "Should not be a full page" }
        }

        @Test
        fun `POST position saves and returns fragment`() {
            val testUser = createUser("alice")
            val portfolio = portfolioService.create(testUser.id!!, "Test", null, "EUR")

            val result = mockMvc.perform(
                post("/portfolios/${portfolio.id}/positions")
                    .with(mockUserDetails(testUser))
                    .with(csrf())
                    .param("ticker", "AMZN")
                    .param("currency", "USD")
                    .param("weightPct", "0.20")
            )
                .andExpect(status().isOk)
                .andReturn()

            val body = result.response.contentAsString
            assert(body.contains("AMZN")) { "Should contain ticker" }

            val positions = positionRepository.findByPortfolioId(portfolio.id!!)
            assert(positions.size == 1)
            assert(positions[0].ticker == "AMZN")
        }

        @Test
        fun `POST position persists the view as return and std-dev`() {
            val testUser = createUser("alice")
            val portfolio = portfolioService.create(testUser.id!!, "Test", null, "EUR")

            mockMvc.perform(
                post("/portfolios/${portfolio.id}/positions")
                    .with(mockUserDetails(testUser))
                    .with(csrf())
                    .param("ticker", "AMZN")
                    .param("currency", "USD")
                    .param("weightPct", "0.20")
                    .param("expectedReturn", "-0.0750")
                    .param("returnStddev", "0.2800")
            )
                .andExpect(status().isOk)

            val positions = positionRepository.findByPortfolioId(portfolio.id!!)
            assert(positions.size == 1)
            assertEquals(0, BigDecimal("-0.0750").compareTo(positions[0].expectedReturn))
            assertEquals(0, BigDecimal("0.2800").compareTo(positions[0].returnStddev))
        }

        @org.junit.jupiter.params.ParameterizedTest(name = "return={0} stddev={1}")
        @org.junit.jupiter.params.provider.CsvSource(
            "0.12,",        // return without its spread
            ",0.30",        // spread without a return
            "0.12,0",       // zero spread would be infinite certainty
            "0.12,-0.10",
            "-1.00,0.30",   // can't lose more than everything
        )
        fun `POST position rejects an incomplete or impossible view`(expectedReturn: String?, returnStddev: String?) {
            val testUser = createUser("alice")
            val portfolio = portfolioService.create(testUser.id!!, "Test", null, "EUR")

            val request = post("/portfolios/${portfolio.id}/positions")
                .with(mockUserDetails(testUser))
                .with(csrf())
                .param("ticker", "AMZN")
                .param("currency", "USD")
                .param("weightPct", "0.20")
            expectedReturn?.let { request.param("expectedReturn", it) }
            returnStddev?.let { request.param("returnStddev", it) }

            mockMvc.perform(request).andExpect(status().isBadRequest)
            assert(positionRepository.findByPortfolioId(portfolio.id!!).isEmpty())
        }

        @Test
        fun `POST cash position cannot carry a view`() {
            val testUser = createUser("alice")
            val portfolio = portfolioService.create(testUser.id!!, "Test", null, "EUR")

            mockMvc.perform(
                post("/portfolios/${portfolio.id}/positions")
                    .with(mockUserDetails(testUser))
                    .with(csrf())
                    .param("positionType", "CASH")
                    .param("ticker", "CASH.USD")
                    .param("currency", "USD")
                    .param("weightPct", "0.20")
                    .param("expectedReturn", "0.03")
                    .param("returnStddev", "0.01")
            )
                .andExpect(status().isBadRequest)
        }

        @Test
        fun `DELETE position returns empty body`() {
            val testUser = createUser("alice")
            val userId = testUser.id!!
            val portfolio = portfolioService.create(userId, "Test", null, "EUR")
            val portfolioId = portfolio.id!!
            val position = portfolioService.addPosition(portfolioId, userId, PositionForm(
                ticker = "GOOG", currency = "USD", weightPct = BigDecimal("0.10"),
            ))

            mockMvc.perform(
                delete("/portfolios/$portfolioId/positions/${position.id}")
                    .with(mockUserDetails(testUser))
                    .with(csrf())
            )
                .andExpect(status().isOk)
                .andExpect(content().string(""))

            assert(positionRepository.findByPortfolioId(portfolioId).isEmpty())
        }

        @Test
        fun `DELETE position without CSRF returns 403`() {
            val testUser = createUser("alice")
            val userId = testUser.id!!
            val portfolio = portfolioService.create(userId, "Test", null, "EUR")
            val portfolioId = portfolio.id!!
            val position = portfolioService.addPosition(portfolioId, userId, PositionForm(
                ticker = "GOOG", currency = "USD", weightPct = BigDecimal("0.10"),
            ))

            mockMvc.perform(
                delete("/portfolios/${portfolio.id}/positions/${position.id}")
                    .with(mockUserDetails(testUser))
            )
                .andExpect(status().isForbidden)
        }
    }

    private fun createUser(username: String): User = userRepository.save(
        User(
            username = username,
            email = "$username@test.com",
            passwordHash = passwordEncoder.encode("testpassword")!!,
            role = "USER",
        )
    )
}
