package com.kernfolio.controller.api

import com.kernfolio.dto.AllocationChartData
import com.kernfolio.dto.DiscreteAllocationData
import com.kernfolio.dto.FrontierChartData
import com.kernfolio.dto.FrontierPointData
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

        val allTickers = (run.results.optimizedWeights.keys + positions.map { it.ticker }).distinct().sorted()

        val optimizedByTicker = run.results.optimizedWeights
        val currentByTicker = positions.associate { it.ticker to it.weightPct.toDouble() }

        return ResponseEntity.ok(
            AllocationChartData(
                labels = allTickers,
                optimizedWeights = allTickers.map { optimizedByTicker[it] ?: 0.0 },
                currentWeights = allTickers.map { currentByTicker[it] ?: 0.0 },
            )
        )
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
