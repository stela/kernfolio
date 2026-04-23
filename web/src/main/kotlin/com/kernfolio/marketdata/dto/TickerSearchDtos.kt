package com.kernfolio.marketdata.dto

import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class SearchTickersRequest(
    val query: String,
    val limit: Int,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class TickerSearchResult(
    val symbol: String,
    val shortname: String? = null,
    val longname: String? = null,
    val exchange: String? = null,
    val quoteType: String? = null,
    val currency: String? = null,
)

data class SearchTickersResponse(
    val results: List<TickerSearchResult>,
)
