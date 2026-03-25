package com.kernfolio.marketdata.dto

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming
import java.math.BigDecimal
import java.time.LocalDate

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class FetchPricesRequest(
    val tickers: List<String>,
    val startDate: LocalDate,
    val endDate: LocalDate,
)

data class PricePoint(
    val date: LocalDate,
    val close: BigDecimal,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TickerMetadata(
    val currency: String,
    val name: String?,
    val marketCap: BigDecimal?,
    val sector: String?,
)

data class FetchPricesResponse(
    val prices: Map<String, List<PricePoint>>,
    val metadata: Map<String, TickerMetadata>,
    val errors: Map<String, String>,
)
