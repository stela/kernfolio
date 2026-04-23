package com.kernfolio.controller

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.User
import com.kernfolio.mockUserDetails
import com.kernfolio.repository.InviteCodeRepository
import com.kernfolio.repository.UserRepository
import com.kernfolio.service.FeatureFlagService
import com.kernfolio.service.UserService
import org.hamcrest.Matchers.containsString
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class AdminControllerTest {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var inviteCodeRepository: InviteCodeRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var userService: UserService
    @Autowired lateinit var featureFlagService: FeatureFlagService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
    }

    @Nested
    inner class AccessControl {

        @Test
        fun `non-admin user gets 403 on admin dashboard`() {
            mockMvc.perform(get("/admin").with(user("alice").roles("USER")))
                .andExpect(status().isForbidden)
        }

        @Test
        fun `non-admin user gets 403 on admin users`() {
            mockMvc.perform(get("/admin/users").with(user("alice").roles("USER")))
                .andExpect(status().isForbidden)
        }

        @Test
        fun `non-admin user gets 403 on admin flags`() {
            mockMvc.perform(get("/admin/flags").with(user("alice").roles("USER")))
                .andExpect(status().isForbidden)
        }

        @Test
        fun `unauthenticated user redirects to login`() {
            mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/login"))
        }
    }

    @Nested
    inner class AdminDashboard {

        @Test
        fun `admin can access dashboard`() {
            val admin = createAdmin("admin1")
            mockMvc.perform(get("/admin").with(mockUserDetails(admin)))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("Admin Panel")))
        }
    }

    @Nested
    inner class UserManagement {

        @Test
        fun `admin can view users page`() {
            val admin = createAdmin("admin2")
            mockMvc.perform(get("/admin/users").with(mockUserDetails(admin)))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("User Management")))
        }

        @Test
        fun `admin can generate invite code`() {
            val admin = createAdmin("admin3")
            mockMvc.perform(
                post("/admin/users/invite")
                    .with(mockUserDetails(admin))
                    .with(csrf())
            )
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/admin/users"))

            val codes = inviteCodeRepository.findByCreatedBy(admin.id!!)
            assert(codes.isNotEmpty()) { "Invite code should be created" }
        }

        @Test
        fun `admin can disable a user`() {
            val admin = createAdmin("admin4")
            val targetUser = createUser("target1")

            val result = mockMvc.perform(
                post("/admin/users/${targetUser.id}/toggle")
                    .with(mockUserDetails(admin))
                    .with(csrf())
                    .param("enabled", "false")
            )
                .andExpect(status().isOk)
                .andReturn()

            val body = result.response.contentAsString
            assert(body.contains("Disabled")) { "Should show disabled status" }
            assert(!body.contains("<html")) { "Should be a fragment" }

            val updated = userRepository.findById(targetUser.id!!).get()
            assert(!updated.enabled) { "User should be disabled" }
        }

        @Test
        fun `admin can enable a user`() {
            val admin = createAdmin("admin5")
            val targetUser = createUser("target2")
            val targetUserId = targetUser.id!!
            userService.setEnabled(targetUserId, false)

            mockMvc.perform(
                post("/admin/users/$targetUserId/toggle")
                    .with(mockUserDetails(admin))
                    .with(csrf())
                    .param("enabled", "true")
            )
                .andExpect(status().isOk)

            val updated = userRepository.findById(targetUserId).get()
            assert(updated.enabled) { "User should be enabled" }
        }
    }

    @Nested
    inner class FlagManagement {

        @Test
        fun `admin can view flags page`() {
            val admin = createAdmin("admin6")
            mockMvc.perform(get("/admin/flags").with(mockUserDetails(admin)))
                .andExpect(status().isOk)
                .andExpect(content().string(containsString("Feature Flags")))
        }

        @Test
        fun `admin can toggle a flag via HTMX`() {
            val admin = createAdmin("admin7")
            val flags = featureFlagService.findAll()
            val flag = flags.first()
            val result = mockMvc.perform(
                post("/admin/flags/${flag.id}")
                    .with(mockUserDetails(admin))
                    .with(csrf())
                    .param("enabled", (!flag.enabled).toString())
                    .param("rolloutPct", "50")
            )
                .andExpect(status().isOk)
                .andReturn()

            val body = result.response.contentAsString
            assert(body.contains("<tr")) { "Should return a table row fragment" }
            assert(!body.contains("<html")) { "Should not be a full page" }
        }
    }

    @Nested
    inner class CsrfProtection {

        @Test
        fun `POST invite without CSRF redirects with sessionExpired flag`() {
            val admin = createAdmin("admin8")
            mockMvc.perform(
                post("/admin/users/invite")
                    .with(mockUserDetails(admin))
            )
                .andExpect(status().is3xxRedirection)
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("sessionExpired=1")))
        }
    }

    private fun createAdmin(username: String): User = userRepository.save(
        User(
            username = username,
            email = "$username@test.com",
            passwordHash = passwordEncoder.encode("testpassword")!!,
            role = "ADMIN",
        )
    )

    private fun createUser(username: String): User = userRepository.save(
        User(
            username = username,
            email = "$username@test.com",
            passwordHash = passwordEncoder.encode("testpassword")!!,
            role = "USER",
        )
    )
}
