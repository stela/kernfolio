package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("invite_codes")
data class InviteCode(
    @Id val id: UUID? = null,
    val code: String,
    val createdBy: UUID,
    val usedBy: UUID? = null,
    val usedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val createdAt: Instant? = null,
)
