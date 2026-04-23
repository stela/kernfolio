package com.kernfolio.controller.api

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.User
import com.kernfolio.mockUserDetails
import com.kernfolio.repository.CachedFxRateRepository
import com.kernfolio.repository.UserRepository
import org.hamcrest.Matchers.closeTo
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
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
import java.time.LocalDate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class FxRateControllerTest {

    companion object {
        // Point optimizer base-url at a closed port so the on-demand FX fetch
        // in getLatestCrossRateInfoOrFetch fails fast (MarketDataException →
        // caught → null) instead of dialing a real optimizer.
        @JvmStatic
        @DynamicPropertySource
        fun optimizerProps(registry: DynamicPropertyRegistry) {
            registry.add("optimizer.base-url") { "http://localhost:9999" }
        }
    }

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var cachedFxRateRepository: CachedFxRateRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()

        // Seed FX rates: EUR/USD=1.085, EUR/JPY=162.5
        cachedFxRateRepository.upsert("EURUSD", LocalDate.now(), BigDecimal("1.08500000"))
        cachedFxRateRepository.upsert("EURJPY", LocalDate.now(), BigDecimal("162.50000000"))
    }

    private fun createUser(): User = userRepository.save(
        User(username = "fxuser", email = "fx@test.com", passwordHash = passwordEncoder.encode("pass")!!, role = "USER")
    )

    @Test
    fun `returns EUR-based rates with timestamps when base is EUR`() {
        val user = createUser()
        mockMvc.perform(
            get("/api/fx/latest")
                .param("base", "EUR")
                .param("currencies", "USD,JPY")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.USD.rate").value(closeTo(1.085, 0.001)))
            .andExpect(jsonPath("$.USD.asOf").isString)
            .andExpect(jsonPath("$.USD.fetchedAt").isString)
            .andExpect(jsonPath("$.JPY.rate").value(closeTo(162.5, 0.1)))
            .andExpect(jsonPath("$.JPY.asOf").isString)
            .andExpect(jsonPath("$.JPY.fetchedAt").isString)
    }

    @Test
    fun `returns cross rates when base is USD`() {
        val user = createUser()
        // USD/JPY = EUR/JPY / EUR/USD = 162.5 / 1.085 ≈ 149.77
        mockMvc.perform(
            get("/api/fx/latest")
                .param("base", "USD")
                .param("currencies", "JPY,EUR")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.JPY.rate").value(closeTo(149.77, 0.1)))
            .andExpect(jsonPath("$.EUR.rate").value(closeTo(0.9217, 0.01)))
    }

    @Test
    fun `returns null for unknown currency`() {
        val user = createUser()
        mockMvc.perform(
            get("/api/fx/latest")
                .param("base", "EUR")
                .param("currencies", "XYZ")
                .with(mockUserDetails(user))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.XYZ").isEmpty())
    }
}
