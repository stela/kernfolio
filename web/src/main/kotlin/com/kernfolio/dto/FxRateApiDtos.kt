package com.kernfolio.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * `asOf` is Frankfurter's reference date for the rate (a plain calendar date).
 * `fetchedAt` is the UTC instant we wrote the entry to our cache — serialized
 * as ISO-8601 with the `Z` suffix. The browser renders both in local time.
 */
data class LatestFxRateDto(
    val rate: BigDecimal,
    val asOf: LocalDate,
    val fetchedAt: Instant,
)
