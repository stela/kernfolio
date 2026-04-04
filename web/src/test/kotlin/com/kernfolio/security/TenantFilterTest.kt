package com.kernfolio.security

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.User
import com.kernfolio.repository.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import com.kernfolio.repository.InviteCodeRepository
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.stereotype.Controller
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TenantFilterTest {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var inviteCodeRepository: InviteCodeRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder

    lateinit var mockMvc: MockMvc
    private lateinit var testUser: User

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
        inviteCodeRepository.deleteAll()
        userRepository.deleteAll()
        testUser = userRepository.save(
            User(
                username = "tenantuser",
                email = "tenant@example.com",
                passwordHash = passwordEncoder.encode("password") ?: "",
                role = "USER",
            )
        )
    }

    @Test
    fun `authenticated request sets tenant context on database connection`() {
        val principal = KernfolioUserDetails(testUser)

        mockMvc.perform(get("/test/tenant-check").with(user(principal)))
            .andExpect(status().isOk)
            .andExpect(content().string(testUser.id.toString()))
    }

    @Test
    fun `admin request sets admin role in tenant context`() {
        val adminUser = userRepository.save(
            User(
                username = "adminuser",
                email = "admin@example.com",
                passwordHash = passwordEncoder.encode("password") ?: "",
                role = "ADMIN",
            )
        )
        val principal = KernfolioUserDetails(adminUser)

        mockMvc.perform(get("/test/tenant-role-check").with(user(principal)))
            .andExpect(status().isOk)
            .andExpect(content().string("ADMIN"))
    }

    @Test
    fun `tenant context is cleared after request`() {
        val principal = KernfolioUserDetails(testUser)

        mockMvc.perform(get("/test/tenant-check").with(user(principal)))
            .andExpect(status().isOk)

        assertEquals(null, TenantContext.get())
    }
}

@Controller
class TenantTestController(private val jdbcTemplate: JdbcTemplate) {

    @GetMapping("/test/tenant-check")
    @ResponseBody
    fun tenantCheck(): String =
        jdbcTemplate.queryForObject("SELECT current_setting('app.current_user_id')", String::class.java) ?: ""

    @GetMapping("/test/tenant-role-check")
    @ResponseBody
    fun tenantRoleCheck(): String =
        jdbcTemplate.queryForObject("SELECT current_setting('app.current_user_role')", String::class.java) ?: ""
}
