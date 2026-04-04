package com.kernfolio.service

import com.kernfolio.domain.User
import com.kernfolio.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.UUID

class UserServiceTest {

    private val userRepository = mockk<UserRepository>()
    private val passwordEncoder = mockk<PasswordEncoder>()
    private lateinit var userService: UserService

    @BeforeEach
    fun setup() {
        userService = UserService(userRepository, passwordEncoder)
    }

    @Nested
    inner class CreateUser {

        @Test
        fun `hashes password with encoder before saving`() {
            val userSlot = slot<User>()
            every { passwordEncoder.encode("plaintext") } returns "\$2a\$10\$hashed"
            every { userRepository.save(capture(userSlot)) } answers { userSlot.captured.copy(id = UUID.randomUUID()) }

            userService.createUser("alice", "alice@example.com", "plaintext")

            assertEquals("\$2a\$10\$hashed", userSlot.captured.passwordHash)
            verify { passwordEncoder.encode("plaintext") }
        }

        @Test
        fun `saves user with correct fields`() {
            val userSlot = slot<User>()
            every { passwordEncoder.encode(any()) } returns "hashed"
            every { userRepository.save(capture(userSlot)) } answers { userSlot.captured.copy(id = UUID.randomUUID()) }

            userService.createUser("bob", "bob@example.com", "secret", "ADMIN")

            assertEquals("bob", userSlot.captured.username)
            assertEquals("bob@example.com", userSlot.captured.email)
            assertEquals("ADMIN", userSlot.captured.role)
        }

        @Test
        fun `defaults role to USER`() {
            val userSlot = slot<User>()
            every { passwordEncoder.encode(any()) } returns "hashed"
            every { userRepository.save(capture(userSlot)) } answers { userSlot.captured.copy(id = UUID.randomUUID()) }

            userService.createUser("carol", "carol@example.com", "secret")

            assertEquals("USER", userSlot.captured.role)
        }
    }

    @Nested
    inner class ExistsByUsername {

        @Test
        fun `returns true when user exists`() {
            every { userRepository.findByUsername("alice") } returns mockk()
            assertTrue(userService.existsByUsername("alice"))
        }

        @Test
        fun `returns false when user does not exist`() {
            every { userRepository.findByUsername("nobody") } returns null
            assertFalse(userService.existsByUsername("nobody"))
        }
    }

    @Nested
    inner class ExistsByEmail {

        @Test
        fun `returns true when email exists`() {
            every { userRepository.findByEmail("alice@example.com") } returns mockk()
            assertTrue(userService.existsByEmail("alice@example.com"))
        }

        @Test
        fun `returns false when email does not exist`() {
            every { userRepository.findByEmail("nobody@example.com") } returns null
            assertFalse(userService.existsByEmail("nobody@example.com"))
        }
    }

    @Nested
    inner class CountUsers {

        @Test
        fun `delegates to repository count`() {
            every { userRepository.count() } returns 42
            assertEquals(42, userService.countUsers())
        }
    }

    @Nested
    inner class FindAll {

        @Test
        fun `delegates to repository findAll`() {
            val users = listOf(mockk<User>())
            every { userRepository.findAll() } returns users
            assertEquals(users, userService.findAll())
        }
    }

    @Nested
    inner class FindById {

        @Test
        fun `returns user when found`() {
            val user = mockk<User>()
            val id = UUID.randomUUID()
            every { userRepository.findById(id) } returns java.util.Optional.of(user)
            assertEquals(user, userService.findById(id))
        }

        @Test
        fun `returns null when not found`() {
            val id = UUID.randomUUID()
            every { userRepository.findById(id) } returns java.util.Optional.empty()
            assertNull(userService.findById(id))
        }
    }

    @Nested
    inner class SetEnabled {

        @Test
        fun `disables user`() {
            val id = UUID.randomUUID()
            val user = User(id = id, username = "alice", email = "a@b.com", passwordHash = "h", enabled = true)
            val userSlot = slot<User>()
            every { userRepository.findById(id) } returns java.util.Optional.of(user)
            every { userRepository.save(capture(userSlot)) } answers { userSlot.captured }

            userService.setEnabled(id, false)

            assertFalse(userSlot.captured.enabled)
        }

        @Test
        fun `enables user`() {
            val id = UUID.randomUUID()
            val user = User(id = id, username = "alice", email = "a@b.com", passwordHash = "h", enabled = false)
            val userSlot = slot<User>()
            every { userRepository.findById(id) } returns java.util.Optional.of(user)
            every { userRepository.save(capture(userSlot)) } answers { userSlot.captured }

            userService.setEnabled(id, true)

            assertTrue(userSlot.captured.enabled)
        }
    }
}
