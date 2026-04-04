package com.kernfolio.dto

data class AllocationChartData(
    val labels: List<String>,
    val optimizedWeights: List<Double>,
    val currentWeights: List<Double>,
)

data class FrontierPointData(
    val risk: Double,
    val ret: Double,
)

data class FrontierChartData(
    val frontier: List<FrontierPointData>,
    val optimized: FrontierPointData?,
)
