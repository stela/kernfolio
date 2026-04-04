package com.kernfolio.dto

import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.annotation.JsonNaming

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OptimizeConstraintsDto(
    val minWeight: Double,
    val maxWeight: Double,
    val longOnly: Boolean,
    val sectorConstraints: Map<String, Double>? = null,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OptimizeRequestDto(
    val algorithm: String,
    val prices: Map<String, List<Any>>,
    val marketCaps: Map<String, Double>,
    val views: Map<String, Double>,
    val confidences: Map<String, Double>,
    val riskFreeRate: Double,
    val tau: Double,
    val kellyFraction: Double,
    val constraints: OptimizeConstraintsDto,
    val sectors: Map<String, String>,
    val covarianceMethod: String,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OptimizeMetricsDto(
    val expectedAnnualReturn: Double,
    val annualVolatility: Double,
    val sharpeRatio: Double,
    @param:JsonProperty("cvar_95")
    val cvar95: Double,
)

data class FrontierPointDto(
    val risk: Double,
    @param:JsonProperty("return")
    val ret: Double,
)

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy::class)
data class OptimizeResponseDto(
    val weights: Map<String, Double>,
    val metrics: OptimizeMetricsDto,
    val efficientFrontier: List<FrontierPointDto>,
    val correlationMatrix: Map<String, Map<String, Double>>,
    val computationMs: Int,
)

data class OptimizeErrorDto(
    val error: String,
    val message: String,
    val detail: String,
)
