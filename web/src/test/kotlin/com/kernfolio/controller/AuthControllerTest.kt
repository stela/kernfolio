package com.kernfolio.controller

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.InviteCode
import com.kernfolio.domain.User
import com.kernfolio.repository.InviteCodeRepository
import com.kernfolio.repository.UserRepository
import com.kernfolio.service.UserService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.model
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class AuthControllerTest {

    @Autowired lateinit var context: WebApplicationContext
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var inviteCodeRepository: InviteCodeRepository
    @Autowired lateinit var passwordEncoder: PasswordEncoder
    @Autowired lateinit var userService: UserService

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
        inviteCodeRepository.deleteAll()
        userRepository.deleteAll()
    }

    @Nested
    inner class LoginPage {

        @Test
        fun `GET login returns 200 with form`() {
            mockMvc.perform(get("/login"))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sign in to Kernfolio")))
        }

        @Test
        fun `login page contains CSRF token`() {
            mockMvc.perform(get("/login"))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"csrf-token\"")))
        }

        @Test
        fun `error param shows error message`() {
            mockMvc.perform(get("/login").param("error", ""))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid username or password")))
        }

        @Test
        fun `logout param shows logout message`() {
            mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("You have been signed out")))
        }

        @Test
        fun `registered param shows success message`() {
            mockMvc.perform(get("/login").param("registered", ""))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Registration successful")))
        }
    }

    @Nested
    inner class RegisterPage {

        @Test
        fun `GET register returns 200 with form`() {
            mockMvc.perform(get("/register"))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Create an account")))
        }

        @Test
        fun `register page contains CSRF token`() {
            mockMvc.perform(get("/register"))
                .andExpect(status().isOk)
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"csrf-token\"")))
        }
    }

    @Nested
    inner class Registration {

        @Test
        fun `successful registration with valid invite code redirects to login`() {
            val admin = createUser("admin", "admin@test.com", "ADMIN")
            val invite = createInviteCode(admin.id!!)

            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "newuser")
                    .param("email", "newuser@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "password123")
                    .param("inviteCode", invite.code)
            )
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/login?registered"))
        }

        @Test
        fun `invalid invite code shows error`() {
            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "newuser")
                    .param("email", "newuser@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "password123")
                    .param("inviteCode", "INVALID")
            )
                .andExpect(status().isOk)
                .andExpect(model().attributeHasFieldErrors("registrationForm", "inviteCode"))
        }

        @Test
        fun `blank invite code shows error`() {
            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "newuser")
                    .param("email", "newuser@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "password123")
                    .param("inviteCode", "")
            )
                .andExpect(status().isOk)
                .andExpect(model().attributeHasFieldErrors("registrationForm", "inviteCode"))
        }

        @Test
        fun `password mismatch shows error`() {
            val admin = createUser("admin", "admin@test.com", "ADMIN")
            val invite = createInviteCode(admin.id!!)

            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "newuser")
                    .param("email", "newuser@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "different")
                    .param("inviteCode", invite.code)
            )
                .andExpect(status().isOk)
                .andExpect(model().attributeHasFieldErrors("registrationForm", "confirmPassword"))
        }

        @Test
        fun `duplicate username or email shows generic error`() {
            val admin = createUser("existing", "existing@test.com", "ADMIN")
            val invite = createInviteCode(admin.id!!)

            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "existing")
                    .param("email", "new@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "password123")
                    .param("inviteCode", invite.code)
            )
                .andExpect(status().isOk)
                .andExpect(model().attributeHasErrors("registrationForm"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Registration could not be completed")))
        }

        @Test
        fun `validation errors for blank fields`() {
            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "")
                    .param("email", "")
                    .param("password", "")
                    .param("confirmPassword", "")
                    .param("inviteCode", "")
            )
                .andExpect(status().isOk)
                .andExpect(model().attributeHasFieldErrors("registrationForm", "username", "email", "password", "confirmPassword"))
        }

        @Test
        fun `short password shows validation error`() {
            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "newuser")
                    .param("email", "newuser@test.com")
                    .param("password", "short")
                    .param("confirmPassword", "short")
                    .param("inviteCode", "TESTCODE")
            )
                .andExpect(status().isOk)
                .andExpect(model().attributeHasFieldErrors("registrationForm", "password"))
        }

        @Test
        fun `first enabled user gets ADMIN role`() {
            // Create system user (disabled) to have an invite code creator
            val systemUser = createUser("__system__", "system@kernfolio.local", "ADMIN", enabled = false)
            val invite = createInviteCode(systemUser.id!!)

            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "firstadmin")
                    .param("email", "admin@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "password123")
                    .param("inviteCode", invite.code)
            )
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/login?registered"))

            val user = userRepository.findByUsername("firstadmin")!!
            assert(user.role == "ADMIN") { "First registered user should be ADMIN, got ${user.role}" }
        }

        @Test
        fun `subsequent users get USER role`() {
            val admin = createUser("admin", "admin@test.com", "ADMIN")
            val invite = createInviteCode(admin.id!!)

            mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "regular")
                    .param("email", "regular@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "password123")
                    .param("inviteCode", invite.code)
            )
                .andExpect(status().is3xxRedirection)

            val user = userRepository.findByUsername("regular")!!
            assert(user.role == "USER") { "Subsequent user should be USER, got ${user.role}" }
        }
    }

    @Nested
    inner class CsrfProtection {

        @Test
        fun `POST register without CSRF token is rejected`() {
            mockMvc.perform(
                post("/register")
                    .param("username", "attacker")
                    .param("email", "attacker@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "password123")
                    .param("inviteCode", "CODE")
            )
                .andExpect(status().isForbidden)
        }
    }

    @Nested
    inner class SecurityHardening {

        @Test
        fun `registration error does not reveal which field caused uniqueness conflict`() {
            val admin = createUser("taken", "taken@test.com", "ADMIN")
            val invite = createInviteCode(admin.id!!)

            // Try with duplicate email but different username
            val result = mockMvc.perform(
                post("/register")
                    .with(csrf())
                    .param("username", "newname")
                    .param("email", "taken@test.com")
                    .param("password", "password123")
                    .param("confirmPassword", "password123")
                    .param("inviteCode", invite.code)
            )
                .andExpect(status().isOk)
                .andReturn()

            val body = result.response.contentAsString
            assert(!body.contains("email already")) { "Should not reveal email-specific error" }
            assert(!body.contains("username already")) { "Should not reveal username-specific error" }
            assert(body.contains("Registration could not be completed")) { "Should show generic message" }
        }
    }

    // Helper methods

    private fun createUser(
        username: String,
        email: String,
        role: String,
        enabled: Boolean = true,
    ): User = userRepository.save(
        User(
            username = username,
            email = email,
            passwordHash = passwordEncoder.encode("testpassword")!!,
            role = role,
            enabled = enabled,
        )
    )

    private fun createInviteCode(createdBy: UUID): InviteCode = inviteCodeRepository.save(
        InviteCode(
            code = "TESTCODE" + UUID.randomUUID().toString().take(4).uppercase(),
            createdBy = createdBy,
        )
    )
}
