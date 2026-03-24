package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Table("cached_fx_rates")
data class CachedFxRate(
    @Id val currencyPair: String,
    val rateDate: LocalDate,
    val rate: BigDecimal,
    val fetchedAt: Instant? = null,
)
