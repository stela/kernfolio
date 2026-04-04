package com.kernfolio.service

import com.kernfolio.domain.InviteCode
import com.kernfolio.repository.InviteCodeRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class InviteCodeServiceTest {

    private val inviteCodeRepository = mockk<InviteCodeRepository>()
    private val fixedClock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC)
    private lateinit var service: InviteCodeService

    @BeforeEach
    fun setup() {
        service = InviteCodeService(inviteCodeRepository, fixedClock)
    }

    @Nested
    inner class GenerateCode {

        @Test
        fun `generates 8-character code and saves`() {
            val slot = slot<InviteCode>()
            every { inviteCodeRepository.save(capture(slot)) } answers { slot.captured.copy(id = UUID.randomUUID()) }

            val createdBy = UUID.randomUUID()
            service.generateCode(createdBy)

            assertEquals(8, slot.captured.code.length)
            assertEquals(createdBy, slot.captured.createdBy)
            verify { inviteCodeRepository.save(any()) }
        }

        @Test
        fun `passes expiration to saved entity`() {
            val slot = slot<InviteCode>()
            every { inviteCodeRepository.save(capture(slot)) } answers { slot.captured.copy(id = UUID.randomUUID()) }

            val expiresAt = Instant.parse("2026-02-01T00:00:00Z")
            service.generateCode(UUID.randomUUID(), expiresAt)

            assertEquals(expiresAt, slot.captured.expiresAt)
        }
    }

    @Nested
    inner class ValidateCode {

        @Test
        fun `returns Valid for unused non-expired code`() {
            val code = InviteCode(
                id = UUID.randomUUID(),
                code = "ABCD1234",
                createdBy = UUID.randomUUID(),
            )
            every { inviteCodeRepository.findByCode("ABCD1234") } returns code

            val result = service.validateCode("ABCD1234")

            assertTrue(result is InviteCodeValidationResult.Valid)
            assertEquals(code, (result as InviteCodeValidationResult.Valid).inviteCode)
        }

        @Test
        fun `returns NotFound for unknown code`() {
            every { inviteCodeRepository.findByCode("UNKNOWN") } returns null

            val result = service.validateCode("UNKNOWN")

            assertTrue(result is InviteCodeValidationResult.NotFound)
        }

        @Test
        fun `returns AlreadyUsed when usedBy is set`() {
            val code = InviteCode(
                id = UUID.randomUUID(),
                code = "USED1234",
                createdBy = UUID.randomUUID(),
                usedBy = UUID.randomUUID(),
                usedAt = Instant.now(),
            )
            every { inviteCodeRepository.findByCode("USED1234") } returns code

            val result = service.validateCode("USED1234")

            assertTrue(result is InviteCodeValidationResult.AlreadyUsed)
        }

        @Test
        fun `returns Expired when expiresAt is in the past`() {
            val code = InviteCode(
                id = UUID.randomUUID(),
                code = "EXPD1234",
                createdBy = UUID.randomUUID(),
                expiresAt = Instant.parse("2025-12-31T00:00:00Z"), // before fixed clock
            )
            every { inviteCodeRepository.findByCode("EXPD1234") } returns code

            val result = service.validateCode("EXPD1234")

            assertTrue(result is InviteCodeValidationResult.Expired)
        }

        @Test
        fun `returns Valid when expiresAt is in the future`() {
            val code = InviteCode(
                id = UUID.randomUUID(),
                code = "FUTR1234",
                createdBy = UUID.randomUUID(),
                expiresAt = Instant.parse("2026-06-01T00:00:00Z"), // after fixed clock
            )
            every { inviteCodeRepository.findByCode("FUTR1234") } returns code

            val result = service.validateCode("FUTR1234")

            assertTrue(result is InviteCodeValidationResult.Valid)
        }
    }

    @Nested
    inner class RedeemCode {

        @Test
        fun `sets usedBy and usedAt and saves`() {
            val codeId = UUID.randomUUID()
            val userId = UUID.randomUUID()
            val existing = InviteCode(
                id = codeId,
                code = "REDM1234",
                createdBy = UUID.randomUUID(),
            )
            val slot = slot<InviteCode>()
            every { inviteCodeRepository.findByCode("REDM1234") } returns existing
            every { inviteCodeRepository.save(capture(slot)) } answers { slot.captured }

            service.redeemCode("REDM1234", userId)

            assertEquals(userId, slot.captured.usedBy)
            assertNotNull(slot.captured.usedAt)
            assertEquals(fixedClock.instant(), slot.captured.usedAt)
        }

        @Test
        fun `throws when code not found`() {
            every { inviteCodeRepository.findByCode("NOPE") } returns null

            assertThrows<IllegalArgumentException> {
                service.redeemCode("NOPE", UUID.randomUUID())
            }
        }
    }
}
