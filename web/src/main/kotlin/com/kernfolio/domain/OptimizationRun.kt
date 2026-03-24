package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

@Table("optimization_runs")
data class OptimizationRun(
    @Id val id: UUID? = null,
    val portfolioId: UUID,
    val algorithm: String,
    val parameters: OptimizationParameters,
    val results: OptimizationResults,
    val status: String = "COMPLETED",
    val errorMessage: String? = null,
    val computationMs: Int? = null,
    val createdAt: Instant? = null,
)

data class OptimizationParameters(
    val riskFreeRate: Double? = null,
    val tau: Double? = null,
    val kellyFraction: Double? = null,
    val covarianceMethod: String? = null,
    val lookbackYears: Int? = null,
    val constraints: OptimizationConstraints? = null,
)

data class OptimizationConstraints(
    val minWeight: Double? = null,
    val maxWeight: Double? = null,
    val longOnly: Boolean? = null,
    val sectorMax: Map<String, Double>? = null,
    val excludePositionTypes: List<String>? = null,
)

data class OptimizationResults(
    val optimizedWeights: Map<String, Double> = emptyMap(),
    val metrics: OptimizationMetrics? = null,
    val correlationClusters: List<CorrelationCluster>? = null,
)

data class OptimizationMetrics(
    val expectedAnnualReturn: Double? = null,
    val annualVolatility: Double? = null,
    val sharpeRatio: Double? = null,
    val cvar95: Double? = null,
    val effectiveNBets: Double? = null,
    val maxSectorWeight: Map<String, Double>? = null,
)

data class CorrelationCluster(
    val name: String,
    val tickers: List<String>,
    val avgCorrelation: Double,
    val effectiveN: Double,
)
