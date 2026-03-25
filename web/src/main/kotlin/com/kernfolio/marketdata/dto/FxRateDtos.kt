package com.kernfolio.marketdata.dto

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.LocalDate

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FetchFxRatesRequest(
    val base: String,
    val currencies: List<String>,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class FetchFxRatesResponse(
    val rates: Map<LocalDate, Map<String, BigDecimal>>,
)
