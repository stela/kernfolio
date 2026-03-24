package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("users")
data class User(
    @Id val id: UUID? = null,
    val username: String,
    val email: String,
    val passwordHash: String,
    val role: String = "USER",
    val enabled: Boolean = true,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
