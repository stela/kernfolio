package com.kernfolio.service

data class TickerSuggestion(
    val ticker: String,
    val name: String?,
    val exchange: String?,
    val currency: String?,
    val sector: String?,
    val source: String,
)
