package com.kernfolio.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback
import java.time.Instant
import java.util.UUID
import com.kernfolio.domain.*

@Configuration
class AuditingConfig {

    @Bean
    fun userBeforeConvert() = BeforeConvertCallback<User> { entity ->
        val now = Instant.now()
        if (entity.id == null) {
            entity.copy(createdAt = now, updatedAt = now)
        } else {
            entity.copy(updatedAt = now)
        }
    }

    @Bean
    fun inviteCodeBeforeConvert() = BeforeConvertCallback<InviteCode> { entity ->
        if (entity.id == null && entity.createdAt == null) {
            entity.copy(createdAt = Instant.now())
        } else {
            entity
        }
    }

    @Bean
    fun portfolioBeforeConvert() = BeforeConvertCallback<Portfolio> { entity ->
        val now = Instant.now()
        if (entity.id == null) {
            entity.copy(createdAt = now, updatedAt = now)
        } else {
            entity.copy(updatedAt = now)
        }
    }

    @Bean
    fun positionBeforeConvert() = BeforeConvertCallback<Position> { entity ->
        val now = Instant.now()
        if (entity.id == null) {
            entity.copy(createdAt = now, updatedAt = now)
        } else {
            entity.copy(updatedAt = now)
        }
    }

    @Bean
    fun optimizationRunBeforeConvert() = BeforeConvertCallback<OptimizationRun> { entity ->
        if (entity.id == null && entity.createdAt == null) {
            entity.copy(createdAt = Instant.now())
        } else {
            entity
        }
    }

    @Bean
    fun featureFlagBeforeConvert() = BeforeConvertCallback<FeatureFlag> { entity ->
        val now = Instant.now()
        if (entity.id == null) {
            entity.copy(createdAt = now, updatedAt = now)
        } else {
            entity.copy(updatedAt = now)
        }
    }
}
