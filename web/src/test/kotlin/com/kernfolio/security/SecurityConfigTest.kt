package com.kernfolio.security

import com.kernfolio.TestcontainersConfiguration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import com.kernfolio.mockUserDetails
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import java.util.UUID
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class SecurityConfigTest {

    @Autowired
    lateinit var context: WebApplicationContext

    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply<DefaultMockMvcBuilder>(springSecurity())
            .build()
    }

    @Nested
    inner class PublicRoutes {

        @Test
        fun `login page is accessible without authentication`() {
            mockMvc.perform(get("/login"))
                .andExpect(status().isOk)
        }

        @Test
        fun `register page is accessible without authentication`() {
            mockMvc.perform(get("/register"))
                .andExpect(status().isOk)
        }

        @Test
        fun `about page is accessible without authentication`() {
            mockMvc.perform(get("/about"))
                .andExpect(status().isOk)
        }

        @Test
        fun `landing page is accessible without authentication`() {
            mockMvc.perform(get("/"))
                .andExpect(status().isOk)
        }

        @Test
        fun `CSS is accessible without authentication`() {
            mockMvc.perform(get("/css/tailwind.css"))
                .andExpect(status().isOk)
        }

        @Test
        fun `JS is accessible without authentication`() {
            mockMvc.perform(get("/js/csrf-fetch.js"))
                .andExpect(status().isOk)
        }
    }

    @Nested
    inner class RedirectLoopPrevention {

        @Test
        fun `GET login returns 200 not redirect`() {
            mockMvc.perform(get("/login"))
                .andExpect(status().isOk)
        }

        @Test
        fun `unauthenticated request to protected page redirects to login which returns 200`() {
            val result = mockMvc.perform(get("/dashboard"))
                .andExpect(status().is3xxRedirection)
                .andReturn()
            val location = result.response.redirectedUrl!!
            assertThat(location).isEqualTo("/login")
            mockMvc.perform(get(location))
                .andExpect(status().isOk)
        }
    }

    @Nested
    inner class ProtectedRoutes {

        @Test
        fun `unauthenticated request to protected route redirects to login`() {
            mockMvc.perform(get("/portfolios"))
                .andExpect(status().is3xxRedirection)
                .andExpect(redirectedUrl("/login"))
        }

        @Test
        fun `unauthenticated request to API returns 401`() {
            mockMvc.perform(get("/api/prices/latest"))
                .andExpect(status().isUnauthorized)
        }

        @Test
        fun `authenticated user can access protected routes`() {
            mockMvc.perform(get("/dashboard").with(mockUserDetails(UUID.randomUUID(), "alice")))
                .andExpect(status().isOk) // 200 — access is granted, dashboard renders
        }
    }

    @Nested
    inner class AdminRoutes {

        @Test
        fun `regular user cannot access admin routes`() {
            mockMvc.perform(get("/admin/dashboard").with(user("alice").roles("USER")))
                .andExpect(status().isForbidden)
        }

        @Test
        fun `admin user can access admin routes`() {
            mockMvc.perform(get("/admin/dashboard").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound) // 404 not 403 — access is granted, no controller yet
        }
    }

    @Nested
    inner class SecurityHeaders {

        @Test
        fun `response includes CSP header`() {
            val result = mockMvc.perform(get("/login"))
                .andExpect(header().exists("Content-Security-Policy"))
                .andReturn()
            val csp = result.response.getHeader("Content-Security-Policy")!!
            assertThat(csp).contains("default-src 'self'")
            assertThat(csp).contains("script-src 'self' 'nonce-")
            assertThat(csp).contains("style-src 'self' 'nonce-")
            assertThat(csp).contains("img-src 'self' data:")
            assertThat(csp).contains("font-src 'self'")
            assertThat(csp).contains("connect-src 'self'")
            assertThat(csp).contains("frame-ancestors 'none'")
            assertThat(csp).contains("form-action 'self'")
        }

        @Test
        fun `response includes X-Content-Type-Options header`() {
            mockMvc.perform(get("/login"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        }

        @Test
        fun `response includes X-Frame-Options header`() {
            mockMvc.perform(get("/login"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
        }
    }
}
