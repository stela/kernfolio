package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("feature_flags")
data class FeatureFlag(
    @Id val id: UUID? = null,
    val flagName: String,
    val enabled: Boolean = false,
    val description: String? = null,
    val allowedUserIds: List<UUID> = emptyList(),
    val rolloutPct: Int = 100,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
