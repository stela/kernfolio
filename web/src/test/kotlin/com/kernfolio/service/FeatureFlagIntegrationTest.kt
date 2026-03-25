package com.kernfolio.service

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.repository.FeatureFlagRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class FeatureFlagIntegrationTest {

    @Autowired lateinit var featureFlagRepository: FeatureFlagRepository

    private val allFlagNames = listOf(
        "ALGO_BLACK_LITTERMAN", "ALGO_MEAN_VARIANCE", "ALGO_KELLY",
        "ALGO_CVAR", "ALGO_HRP", "SHOW_EFFICIENT_FRONTIER",
        "SHOW_CORRELATION_HEATMAP", "SHOW_CLUSTER_ANALYSIS",
        "SELF_REGISTRATION", "IMPORT_IBKR",
    )

    private val phase1EnabledFlags = setOf(
        "ALGO_BLACK_LITTERMAN", "ALGO_MEAN_VARIANCE", "SHOW_EFFICIENT_FRONTIER",
    )

    @Test
    fun `all 10 flags are seeded after migration`() {
        val flags = featureFlagRepository.findAll()
        assertEquals(10, flags.size)
        val names = flags.map { it.flagName }.toSet()
        allFlagNames.forEach { assertNotNull(names.contains(it), "Missing flag: $it") }
    }

    @Test
    fun `phase 1 flags are enabled`() {
        phase1EnabledFlags.forEach { name ->
            val flag = featureFlagRepository.findByFlagName(name)
            assertNotNull(flag, "Flag $name should exist")
            assertTrue(flag!!.enabled, "Flag $name should be enabled")
        }
    }

    @Test
    fun `non-phase-1 flags are disabled`() {
        val disabledFlags = allFlagNames - phase1EnabledFlags
        disabledFlags.forEach { name ->
            val flag = featureFlagRepository.findByFlagName(name)
            assertNotNull(flag, "Flag $name should exist")
            assertFalse(flag!!.enabled, "Flag $name should be disabled")
        }
    }

    @Test
    fun `all flags have descriptions`() {
        featureFlagRepository.findAll().forEach { flag ->
            assertNotNull(flag.description, "Flag ${flag.flagName} should have a description")
            assertTrue(flag.description!!.isNotBlank(), "Flag ${flag.flagName} description should not be blank")
        }
    }
}
