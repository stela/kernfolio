package com.kernfolio.service

import com.kernfolio.repository.FeatureFlagRepository
import org.springframework.stereotype.Service
import java.util.UUID
import kotlin.math.absoluteValue

@Service
class FeatureFlagService(private val featureFlagRepository: FeatureFlagRepository) {

    fun isGloballyEnabled(flagName: String): Boolean {
        val flag = featureFlagRepository.findByFlagName(flagName) ?: return false
        return flag.enabled
    }

    fun isEnabled(flagName: String, userId: UUID): Boolean {
        val flag = featureFlagRepository.findByFlagName(flagName) ?: return false
        if (!flag.enabled) return false
        if (flag.allowedUserIds.isNotEmpty() && userId !in flag.allowedUserIds) return false
        if (flag.rolloutPct < 100) {
            return (userId.hashCode().absoluteValue % 100) < flag.rolloutPct
        }
        return true
    }
}
