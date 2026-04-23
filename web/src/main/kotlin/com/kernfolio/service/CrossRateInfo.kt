package com.kernfolio.service

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * A cross-rate plus provenance: the calendar date the rate is effective
 * (Frankfurter's `rate_date`) and the UTC instant we wrote it to our cache.
 * When the cross is composed from two legs, both stamps are taken from the
 * older leg so "as of X" is never optimistic.
 */
data class CrossRateInfo(
    val rate: BigDecimal,
    val asOf: LocalDate,
    val fetchedAt: Instant,
)
