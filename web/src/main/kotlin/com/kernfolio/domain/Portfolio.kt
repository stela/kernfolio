package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("portfolios")
data class Portfolio(
    @Id val id: UUID? = null,
    val userId: UUID,
    val name: String,
    val description: String? = null,
    val baseCurrency: String = "EUR",
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
