package com.kernfolio.dto

import java.math.BigDecimal

data class PortfolioEntryData(
    val baseCurrency: String,
    val positions: List<PositionEntryDto>,
)

data class PositionEntryDto(
    val id: String,
    val positionType: String,
    val ticker: String,
    val currency: String,
    val weightPct: BigDecimal,
    val costBasisPct: BigDecimal?,
    val intrinsicValueLocal: BigDecimal?,
    val confidencePct: BigDecimal?,
    val sector: String?,
)
