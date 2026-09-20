package com.kernfolio.dto

// All weights are fractions of the whole portfolio. For runs sized with
// Kelly, cash is part of the recommendation: the run's cash weight is spread
// over the portfolio's CASH.<CUR> positions (cashTargets = true), and whatever
// has no cash position to land in is reported as unallocatedCash.
data class AllocationChartData(
    val labels: List<String>,
    val optimizedWeights: List<Double>,
    val currentWeights: List<Double>,
    val cashTargets: Boolean = false,
    val unallocatedCash: Double = 0.0,
    // Weight each of the user's views got against the market prior (0..1).
    val viewConfidences: Map<String, Double> = emptyMap(),
)

data class FrontierPointData(
    val risk: Double,
    val ret: Double,
)

data class FrontierChartData(
    val frontier: List<FrontierPointData>,
    val optimized: FrontierPointData?,
)

data class DiscreteAllocationData(
    val weights: Map<String, Double>,
    val baseCurrency: String,
    val tickers: List<String>,
    val fractional: Map<String, Boolean>,
)

// Headline numbers for the results page. Sent raw (fractions, ISO instant)
// — the browser formats them in the viewer's locale and timezone.
data class RunSummaryData(
    val createdAt: java.time.Instant?,
    val expectedAnnualReturn: Double?,
    val annualVolatility: Double?,
    val sharpeRatio: Double?,
    val cvar95: Double?,
    // null for runs from before Kelly sizing (always fully invested).
    val cashWeight: Double?,
)
