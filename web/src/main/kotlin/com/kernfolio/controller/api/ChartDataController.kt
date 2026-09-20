package com.kernfolio.controller.api

import com.kernfolio.dto.AllocationChartData
import com.kernfolio.dto.DiscreteAllocationData
import com.kernfolio.dto.FrontierChartData
import com.kernfolio.dto.FrontierPointData
import com.kernfolio.dto.RunSummaryData
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.OptimizationRunRepository
import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.PortfolioService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/portfolios/{portfolioId}/runs/{runId}")
class ChartDataController(
    private val portfolioService: PortfolioService,
    private val optimizationRunRepository: OptimizationRunRepository,
    private val instrumentRepository: InstrumentRepository,
) {

    @GetMapping("/allocation-data")
    fun allocationData(
        @PathVariable portfolioId: UUID,
        @PathVariable runId: UUID,
        authentication: Authentication,
    ): ResponseEntity<AllocationChartData> {
        val userId = currentUserId(authentication)
        val (run, positions) = loadRunAndPositions(portfolioId, runId, userId)

        val cashPositions = positions.filter { it.positionType == "CASH" }
        val cashWeight = run.results.cashWeight
        val cashTargets = cashWeight?.let { splitCash(it, cashPositions) } ?: emptyMap()
        // Nowhere to put it: shown as its own row, left for the user to act on.
        val unallocatedCash = if (cashWeight != null && cashPositions.isEmpty() && cashWeight > DISPLAY_EPSILON) cashWeight else 0.0

        val optimizedByTicker = run.results.optimizedWeights + cashTargets
        val currentByTicker = positions.associate { it.ticker to it.weightPct.toDouble() }
        val allTickers = (optimizedByTicker.keys + currentByTicker.keys).distinct().sorted()

        val labels = if (unallocatedCash > 0) allTickers + UNALLOCATED_CASH_LABEL else allTickers
        return ResponseEntity.ok(
            AllocationChartData(
                labels = labels,
                optimizedWeights = allTickers.map { optimizedByTicker[it] ?: 0.0 } + listOfNotNull(unallocatedCash.takeIf { it > 0 }),
                currentWeights = allTickers.map { currentByTicker[it] ?: 0.0 } + listOfNotNull(0.0.takeIf { unallocatedCash > 0 }),
                cashTargets = cashWeight != null,
                unallocatedCash = unallocatedCash,
                viewConfidences = run.results.viewConfidences ?: emptyMap(),
            )
        )
    }

    // The optimizer decides how much cash, not in which currency: keep the
    // user's current split between their cash positions.
    private fun splitCash(cashWeight: Double, cashPositions: List<com.kernfolio.domain.Position>): Map<String, Double> {
        if (cashPositions.isEmpty()) return emptyMap()
        val currentTotal = cashPositions.sumOf { it.weightPct.toDouble() }
        return cashPositions.associate { pos ->
            val share = if (currentTotal > 0) pos.weightPct.toDouble() / currentTotal else 1.0 / cashPositions.size
            pos.ticker to cashWeight * share
        }
    }

    @GetMapping("/frontier-data")
    fun frontierData(
        @PathVariable portfolioId: UUID,
        @PathVariable runId: UUID,
        authentication: Authentication,
    ): ResponseEntity<FrontierChartData> {
        val userId = currentUserId(authentication)
        val run = loadRun(portfolioId, runId, userId)

        val frontier = run.results.efficientFrontier.map {
            FrontierPointData(risk = it.risk, ret = it.ret)
        }

        val optimized = run.results.metrics?.let {
            FrontierPointData(risk = it.annualVolatility ?: 0.0, ret = it.expectedAnnualReturn ?: 0.0)
        }

        return ResponseEntity.ok(FrontierChartData(frontier = frontier, optimized = optimized))
    }

    @GetMapping("/summary-data")
    fun summaryData(
        @PathVariable portfolioId: UUID,
        @PathVariable runId: UUID,
        authentication: Authentication,
    ): ResponseEntity<RunSummaryData> {
        val userId = currentUserId(authentication)
        val run = loadRun(portfolioId, runId, userId)
        val metrics = run.results.metrics

        return ResponseEntity.ok(
            RunSummaryData(
                createdAt = run.createdAt,
                expectedAnnualReturn = metrics?.expectedAnnualReturn,
                annualVolatility = metrics?.annualVolatility,
                sharpeRatio = metrics?.sharpeRatio,
                cvar95 = metrics?.cvar95,
                cashWeight = run.results.cashWeight,
            )
        )
    }

    @GetMapping("/discrete-data")
    fun discreteData(
        @PathVariable portfolioId: UUID,
        @PathVariable runId: UUID,
        authentication: Authentication,
    ): ResponseEntity<DiscreteAllocationData> {
        val userId = currentUserId(authentication)
        val (run, positions) = loadRunAndPositions(portfolioId, runId, userId)
        val portfolio = portfolioService.findByIdAndUserId(portfolioId, userId)!!

        val tickers = run.results.optimizedWeights.keys.toList().sorted()
        val instruments = instrumentRepository.findByTickers(tickers)
        val fractionalByTicker = instruments.associate { it.ticker to it.fractional }

        // CASH positions are always fractional
        val cashTickers = positions.filter { it.positionType == "CASH" }.map { it.ticker }.toSet()

        val fractional = tickers.associateWith { ticker ->
            cashTickers.contains(ticker) || (fractionalByTicker[ticker] ?: false)
        }

        return ResponseEntity.ok(
            DiscreteAllocationData(
                weights = run.results.optimizedWeights,
                baseCurrency = portfolio.baseCurrency,
                tickers = tickers,
                fractional = fractional,
            )
        )
    }

    private companion object {
        const val UNALLOCATED_CASH_LABEL = "Cash (unallocated)"
        const val DISPLAY_EPSILON = 0.00005
    }

    private fun loadRun(portfolioId: UUID, runId: UUID, userId: UUID): com.kernfolio.domain.OptimizationRun {
        portfolioService.findByIdAndUserId(portfolioId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val run = optimizationRunRepository.findById(runId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (run.portfolioId != portfolioId) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return run
    }

    private fun loadRunAndPositions(
        portfolioId: UUID,
        runId: UUID,
        userId: UUID,
    ): Pair<com.kernfolio.domain.OptimizationRun, List<com.kernfolio.domain.Position>> {
        val run = loadRun(portfolioId, runId, userId)
        val positions = portfolioService.findPositionsByPortfolioId(portfolioId, userId)
        return run to positions
    }

    private fun currentUserId(authentication: Authentication): UUID =
        (authentication.principal as KernfolioUserDetails).id
}
