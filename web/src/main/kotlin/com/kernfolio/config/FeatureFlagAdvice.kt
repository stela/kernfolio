package com.kernfolio.config

import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.FeatureFlagService
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute
import java.util.UUID

@ControllerAdvice
class FeatureFlagAdvice(private val featureFlagService: FeatureFlagService) {

    @ModelAttribute("featureFlags")
    fun featureFlags(): FeatureFlagAccessor = FeatureFlagAccessor(featureFlagService, currentUserId())

    private fun currentUserId(): UUID? {
        val principal = SecurityContextHolder.getContext().authentication?.principal
        return (principal as? KernfolioUserDetails)?.id
    }
}

class FeatureFlagAccessor(
    private val service: FeatureFlagService,
    private val userId: UUID?,
) {
    fun isEnabled(flagName: String): Boolean {
        val id = userId ?: return false
        return service.isEnabled(flagName, id)
    }
}
