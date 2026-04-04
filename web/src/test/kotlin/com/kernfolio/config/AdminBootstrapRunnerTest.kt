package com.kernfolio.config

import com.kernfolio.domain.InviteCode
import com.kernfolio.domain.User
import com.kernfolio.service.InviteCodeService
import com.kernfolio.service.UserService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID

class AdminBootstrapRunnerTest {

    private val userService = mockk<UserService>()
    private val inviteCodeService = mockk<InviteCodeService>()
    private lateinit var runner: AdminBootstrapRunner

    @BeforeEach
    fun setup() {
        runner = AdminBootstrapRunner(userService, inviteCodeService)
    }

    @Nested
    inner class WhenNoUsersExist {

        @Test
        fun `creates system user and generates invite code`() {
            val systemUserId = UUID.randomUUID()
            every { userService.countUsers() } returns 0
            every { userService.createUser(any(), any(), any(), any(), any()) } returns User(
                id = systemUserId,
                username = "__system__",
                email = "system@kernfolio.local",
                passwordHash = "hashed",
                role = "ADMIN",
            )
            every { inviteCodeService.generateCode(any()) } returns InviteCode(
                id = UUID.randomUUID(),
                code = "TESTCODE",
                createdBy = systemUserId,
            )

            runner.run(mockk())

            verify { userService.createUser("__system__", "system@kernfolio.local", any(), "ADMIN", false) }
            verify { inviteCodeService.generateCode(systemUserId) }
        }
    }

    @Nested
    inner class WhenUsersExist {

        @Test
        fun `does nothing`() {
            every { userService.countUsers() } returns 5

            runner.run(mockk())

            verify(exactly = 0) { userService.createUser(any(), any(), any(), any(), any()) }
            verify(exactly = 0) { inviteCodeService.generateCode(any()) }
        }
    }
}
