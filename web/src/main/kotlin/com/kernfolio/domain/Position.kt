package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Table("positions")
data class Position(
    @Id val id: UUID? = null,
    val portfolioId: UUID,
    val positionType: String = "EQUITY",
    val ticker: String,
    val currency: String,
    val weightPct: BigDecimal,
    val costBasisPct: BigDecimal? = null,
    val intrinsicValueLocal: BigDecimal? = null,
    val confidencePct: BigDecimal? = null,
    val sector: String? = null,
    val notes: String? = null,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null,
)
