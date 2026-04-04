package com.kernfolio.controller

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.User
import com.kernfolio.mockUserDetails
import com.kernfolio.repository.InviteCodeRepository
import com.kernfolio.repository.PortfolioRepository
import com.kernfolio.repository.UserRepository
import com.kernfolio.service.PortfolioService
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class DashboardControllerTest {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var portfolioRepository: PortfolioRepository
    @Autowired lateinit var inviteCodeRepository: InviteCodeRepository
    @Autowired lateinit var portfolioService: PortfolioService
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
        portfolioRepository.deleteAll()
        inviteCodeRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Nested
    inner class LandingPage {

        @Test
        fun `unauthenticated request to root returns landing page`() {
            mockMvc.perform(get("/"))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("Kernfolio")))
        }

        @Test
        fun `authenticated request to root redirects to dashboard`() {
            mockMvc.perform(get("/").with(user("alice").roles("USER")))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/dashboard"))
        }
    }

    @Nested
    inner class Dashboard {

        @Test
        fun `unauthenticated request to dashboard redirects to login`() {
            mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/login"))
        }

        @Test
        fun `shows empty state when no portfolios exist`() {
            val testUser = createUser("alice")
            mockMvc.perform(get("/dashboard").with(mockUserDetails(testUser)))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("No portfolios yet")))
        }

        @Test
        fun `shows portfolio list when portfolios exist`() {
            val testUser = createUser("alice")
            portfolioService.create(testUser.id!!, "My Portfolio", "test", "EUR")

            mockMvc.perform(get("/dashboard").with(mockUserDetails(testUser)))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("My Portfolio")))
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
