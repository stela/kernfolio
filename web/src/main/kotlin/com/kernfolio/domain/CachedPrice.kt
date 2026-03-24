package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Table("cached_prices")
data class CachedPrice(
    @Id val ticker: String,
    val priceDate: LocalDate,
    val closePrice: BigDecimal,
    val currency: String,
    val fetchedAt: Instant? = null,
)
