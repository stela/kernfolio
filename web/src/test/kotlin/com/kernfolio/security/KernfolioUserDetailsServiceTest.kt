package com.kernfolio.security

import com.kernfolio.domain.User
import com.kernfolio.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.userdetails.UsernameNotFoundException
import java.time.Instant
import java.util.UUID

class KernfolioUserDetailsServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val service = KernfolioUserDetailsService(userRepository)

    private val testUser = User(
        id = UUID.randomUUID(),
        username = "alice",
        email = "alice@example.com",
        passwordHash = "\$2a\$10\$hashedpassword",
        role = "USER",
        enabled = true,
        createdAt = Instant.now(),
        updatedAt = Instant.now(),
    )

    @Test
    fun `loads user by username with correct authorities`() {
        every { userRepository.findByUsername("alice") } returns testUser

        val details = service.loadUserByUsername("alice")

        assertEquals("alice", details.username)
        assertEquals(testUser.passwordHash, details.password)
        assertTrue(details.isEnabled)
        assertEquals(1, details.authorities.size)
        assertEquals("ROLE_USER", details.authorities.first().authority)
    }

    @Test
    fun `loads admin user with ROLE_ADMIN authority`() {
        val adminUser = testUser.copy(role = "ADMIN")
        every { userRepository.findByUsername("alice") } returns adminUser

        val details = service.loadUserByUsername("alice")

        assertEquals("ROLE_ADMIN", details.authorities.first().authority)
    }

    @Test
    fun `throws UsernameNotFoundException for unknown user`() {
        every { userRepository.findByUsername("unknown") } returns null

        assertThrows<UsernameNotFoundException> {
            service.loadUserByUsername("unknown")
        }
    }

    @Test
    fun `disabled user still loads with isEnabled false`() {
        val disabledUser = testUser.copy(enabled = false)
        every { userRepository.findByUsername("alice") } returns disabledUser

        val details = service.loadUserByUsername("alice")

        assertFalse(details.isEnabled)
    }

    @Test
    fun `exposes user id via KernfolioUserDetails`() {
        every { userRepository.findByUsername("alice") } returns testUser

        val details = service.loadUserByUsername("alice") as KernfolioUserDetails

        assertEquals(testUser.id, details.id)
        assertEquals("USER", details.role)
    }
}
