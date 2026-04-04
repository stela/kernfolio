package com.kernfolio.service

import com.kernfolio.domain.FeatureFlag
import com.kernfolio.repository.FeatureFlagRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Optional
import java.util.UUID

class FeatureFlagServiceTest {

    private val repository = mockk<FeatureFlagRepository>()
    private val service = FeatureFlagService(repository)

    private val userId = UUID.randomUUID()

    private fun flag(
        enabled: Boolean = true,
        allowedUserIds: List<UUID> = emptyList(),
        rolloutPct: Int = 100,
    ) = FeatureFlag(
        id = UUID.randomUUID(),
        flagName = "TEST_FLAG",
        enabled = enabled,
        description = "test",
        allowedUserIds = allowedUserIds,
        rolloutPct = rolloutPct,
    )

    @Test
    fun `returns false when flag does not exist`() {
        every { repository.findByFlagName("MISSING") } returns null
        assertFalse(service.isEnabled("MISSING", userId))
    }

    @Test
    fun `returns false when flag is disabled`() {
        every { repository.findByFlagName("TEST_FLAG") } returns flag(enabled = false)
        assertFalse(service.isEnabled("TEST_FLAG", userId))
    }

    @Test
    fun `returns true when flag is enabled with no restrictions`() {
        every { repository.findByFlagName("TEST_FLAG") } returns flag()
        assertTrue(service.isEnabled("TEST_FLAG", userId))
    }

    @Test
    fun `returns true when user is in allowed list`() {
        every { repository.findByFlagName("TEST_FLAG") } returns flag(allowedUserIds = listOf(userId))
        assertTrue(service.isEnabled("TEST_FLAG", userId))
    }

    @Test
    fun `returns false when user is not in allowed list`() {
        val otherUser = UUID.randomUUID()
        every { repository.findByFlagName("TEST_FLAG") } returns flag(allowedUserIds = listOf(otherUser))
        assertFalse(service.isEnabled("TEST_FLAG", userId))
    }

    @Test
    fun `rollout percentage is deterministic based on user id`() {
        every { repository.findByFlagName("TEST_FLAG") } returns flag(rolloutPct = 50)

        val result1 = service.isEnabled("TEST_FLAG", userId)
        val result2 = service.isEnabled("TEST_FLAG", userId)
        // Same user, same flag → same result
        assertTrue(result1 == result2)
    }

    @Test
    fun `rollout percentage zero disables for all users`() {
        every { repository.findByFlagName("TEST_FLAG") } returns flag(rolloutPct = 0)
        assertFalse(service.isEnabled("TEST_FLAG", userId))
    }

    @Test
    fun `empty allowed list means no user restriction`() {
        every { repository.findByFlagName("TEST_FLAG") } returns flag(allowedUserIds = emptyList())
        assertTrue(service.isEnabled("TEST_FLAG", userId))
    }

    @Nested
    inner class FindAll {

        @Test
        fun `returns all flags`() {
            val flags = listOf(flag(), flag(enabled = false))
            every { repository.findAll() } returns flags
            assertEquals(flags, service.findAll())
        }
    }

    @Nested
    inner class UpdateFlag {

        @Test
        fun `updates enabled and rollout`() {
            val flagId = UUID.randomUUID()
            val existing = flag().copy(id = flagId, enabled = false, rolloutPct = 50)
            val slot = slot<FeatureFlag>()
            every { repository.findById(flagId) } returns Optional.of(existing)
            every { repository.save(capture(slot)) } answers { slot.captured }

            service.updateFlag(flagId, enabled = true, rolloutPct = 75)

            assertTrue(slot.captured.enabled)
            assertEquals(75, slot.captured.rolloutPct)
        }
    }
}
