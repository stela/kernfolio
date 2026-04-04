package com.kernfolio.service

import com.kernfolio.domain.OptimizationConstraints
import com.kernfolio.domain.OptimizationMetrics
import com.kernfolio.domain.OptimizationParameters
import com.kernfolio.domain.OptimizationResults
import com.kernfolio.domain.OptimizationRun
import com.kernfolio.domain.FrontierPoint
import com.kernfolio.domain.Position
import com.kernfolio.dto.OptimizeConstraintsDto
import com.kernfolio.dto.OptimizeErrorDto
import com.kernfolio.dto.OptimizeRequestDto
import com.kernfolio.dto.OptimizeResponseDto
import com.kernfolio.marketdata.TickerMapper
import com.kernfolio.repository.CachedPriceRepository
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.OptimizationRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.math.pow

class OptimizationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

@Service
class OptimizerService(
    private val portfolioService: PortfolioService,
    private val cachedPriceRepository: CachedPriceRepository,
    private val instrumentRepository: InstrumentRepository,
    private val currencyConversionService: CurrencyConversionService,
    private val tickerMapper: TickerMapper,
    private val optimizationRunRepository: OptimizationRunRepository,
    private val optimizerWebClient: WebClient,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OptimizerService::class.java)

    fun optimize(
        portfolioId: UUID,
        userId: UUID,
        algorithm: String,
        covarianceMethod: String = "ledoit_wolf",
        riskFreeRate: Double = 0.03,
        tau: Double = 0.05,
        kellyFraction: Double = 0.5,
        minWeight: Double = 0.0,
        maxWeight: Double = 0.40,
        longOnly: Boolean = true,
    ): OptimizationRun {
        val portfolio = portfolioService.findByIdAndUserId(portfolioId, userId)
            ?: throw PortfolioNotFoundException("Portfolio not found")
        val baseCurrency = portfolio.baseCurrency

        val params = OptimizationParameters(
            riskFreeRate = riskFreeRate,
            tau = tau,
            kellyFraction = kellyFraction,
            covarianceMethod = covarianceMethod,
            lookbackYears = LOOKBACK_YEARS,
            constraints = OptimizationConstraints(
                minWeight = minWeight,
                maxWeight = maxWeight,
                longOnly = longOnly,
            ),
        )

        val positions = loadEquityPositions(portfolioId, userId)
        val priceMatrix = buildPriceMatrix(positions, baseCurrency)
        val (views, confidences) = computeViewsAndConfidences(positions)
        val marketCaps = loadMarketCaps(positions)
        val sectors = buildSectorsMap(positions)
        val request = assembleRequest(algorithm, priceMatrix, marketCaps, views, confidences, sectors, params)

        return try {
            val response = callOptimizer(request)
            saveResult(portfolioId, algorithm, params, response)
        } catch (e: OptimizationException) {
            val failedRun = OptimizationRun(
                portfolioId = portfolioId,
                algorithm = algorithm,
                parameters = params,
                results = OptimizationResults(),
                status = "FAILED",
                errorMessage = e.message,
            )
            optimizationRunRepository.save(failedRun)
            throw e
        }
    }

    private fun loadEquityPositions(portfolioId: UUID, userId: UUID): List<Position> {
        val positions = portfolioService.findPositionsByPortfolioId(portfolioId, userId)
            .filter { it.positionType == "EQUITY" }
        if (positions.isEmpty()) {
            throw OptimizationException("No equity positions found in portfolio")
        }
        return positions
    }

    private data class PriceMatrix(
        val dates: List<LocalDate>,
        val pricesByTicker: Map<String, List<Double>>,
    )

    private fun buildPriceMatrix(positions: List<Position>, baseCurrency: String): PriceMatrix {
        val endDate = LocalDate.now()
        val startDate = endDate.minusYears(LOOKBACK_YEARS.toLong())
        val tickers = positions.map { it.ticker }.distinct()

        val allPrices = cachedPriceRepository.findByTickersAndDateRange(tickers, startDate, endDate)
        val pricesByTicker = allPrices.groupBy { it.ticker }

        val convertedByTicker = mutableMapOf<String, Map<LocalDate, Double>>()
        for (ticker in tickers) {
            val tickerPrices = pricesByTicker[ticker]
            if (tickerPrices.isNullOrEmpty()) {
                log.warn("No cached prices for ticker {}, excluding from optimization", ticker)
                continue
            }
            val currency = tickerPrices.first().currency
            val converted = currencyConversionService.convertTo(tickerPrices, currency, baseCurrency)
            convertedByTicker[ticker] = converted.associate { (date, price) -> date to price.toDouble() }
        }

        if (convertedByTicker.isEmpty()) {
            throw OptimizationException("No price data available for any position")
        }

        // Align to common date set
        val commonDates = convertedByTicker.values
            .map { it.keys }
            .reduce { acc, dates -> acc.intersect(dates) }
            .sorted()

        if (commonDates.isEmpty()) {
            throw OptimizationException("No overlapping price dates across positions")
        }

        val yfinancePrices = convertedByTicker.map { (ticker, dateMap) ->
            tickerMapper.toYfinance(ticker) to commonDates.map { date -> dateMap.getValue(date) }
        }.toMap()

        return PriceMatrix(dates = commonDates, pricesByTicker = yfinancePrices)
    }

    private fun computeViewsAndConfidences(
        positions: List<Position>,
    ): Pair<Map<String, Double>, Map<String, Double>> {
        val views = mutableMapOf<String, Double>()
        val confidences = mutableMapOf<String, Double>()

        for (pos in positions) {
            val iv = pos.intrinsicValueLocal ?: continue
            val conf = pos.confidencePct ?: continue

            val latestPrice = cachedPriceRepository.findLatestByTicker(pos.ticker)
                ?: continue
            val price = latestPrice.closePrice

            if (price.compareTo(BigDecimal.ZERO) == 0) continue

            val cagr = computeCagr(iv.toDouble(), price.toDouble())
            val yfinanceTicker = tickerMapper.toYfinance(pos.ticker)
            views[yfinanceTicker] = cagr
            confidences[yfinanceTicker] = conf.toDouble() / 100.0
        }

        return views to confidences
    }

    private fun loadMarketCaps(positions: List<Position>): Map<String, Double> {
        val tickers = positions.map { it.ticker }.distinct()
        val instruments = instrumentRepository.findByTickers(tickers)
        val tickerToMarketCap = instruments.associate { it.ticker to (it.marketCapUsd?.toDouble() ?: 0.0) }

        return tickers.associate { ticker ->
            val yTicker = tickerMapper.toYfinance(ticker)
            val cap = tickerToMarketCap[ticker] ?: 0.0
            if (cap == 0.0) log.warn("No market cap data for {}", ticker)
            yTicker to cap
        }
    }

    private fun buildSectorsMap(positions: List<Position>): Map<String, String> =
        positions.associate { tickerMapper.toYfinance(it.ticker) to (it.sector ?: "Unknown") }

    private fun assembleRequest(
        algorithm: String,
        priceMatrix: PriceMatrix,
        marketCaps: Map<String, Double>,
        views: Map<String, Double>,
        confidences: Map<String, Double>,
        sectors: Map<String, String>,
        params: OptimizationParameters,
    ): OptimizeRequestDto {
        val prices = mutableMapOf<String, List<Any>>()
        prices["dates"] = priceMatrix.dates.map { it.toString() }
        priceMatrix.pricesByTicker.forEach { (ticker, priceList) ->
            prices[ticker] = priceList
        }

        return OptimizeRequestDto(
            algorithm = algorithm,
            prices = prices,
            marketCaps = marketCaps,
            views = views,
            confidences = confidences,
            riskFreeRate = params.riskFreeRate ?: 0.03,
            tau = params.tau ?: 0.05,
            kellyFraction = params.kellyFraction ?: 0.5,
            constraints = OptimizeConstraintsDto(
                minWeight = params.constraints?.minWeight ?: 0.0,
                maxWeight = params.constraints?.maxWeight ?: 0.40,
                longOnly = params.constraints?.longOnly ?: true,
            ),
            sectors = sectors,
            covarianceMethod = params.covarianceMethod ?: "ledoit_wolf",
        )
    }

    private fun callOptimizer(request: OptimizeRequestDto): OptimizeResponseDto {
        log.info("Calling optimizer with algorithm={}, {} tickers", request.algorithm, request.prices.size - 1)
        try {
            return optimizerWebClient.post()
                .uri("/optimize")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(OptimizeResponseDto::class.java)
                .block()
                ?: throw OptimizationException("Empty response from /optimize")
        } catch (e: WebClientResponseException) {
            val errorBody = try {
                objectMapper.readValue(e.responseBodyAsString, OptimizeErrorDto::class.java)
            } catch (_: Exception) {
                null
            }
            throw OptimizationException(
                errorBody?.message ?: "Optimizer returned ${e.statusCode}",
                e,
            )
        } catch (e: OptimizationException) {
            throw e
        } catch (e: Exception) {
            throw OptimizationException("Failed to call optimizer", e)
        }
    }

    private fun saveResult(
        portfolioId: UUID,
        algorithm: String,
        params: OptimizationParameters,
        response: OptimizeResponseDto,
    ): OptimizationRun {
        val internalWeights = response.weights.entries.associate { (yTicker, weight) ->
            tickerMapper.toInternal(yTicker) to weight
        }

        val run = OptimizationRun(
            portfolioId = portfolioId,
            algorithm = algorithm,
            parameters = params,
            results = OptimizationResults(
                optimizedWeights = internalWeights,
                metrics = OptimizationMetrics(
                    expectedAnnualReturn = response.metrics.expectedAnnualReturn,
                    annualVolatility = response.metrics.annualVolatility,
                    sharpeRatio = response.metrics.sharpeRatio,
                    cvar95 = response.metrics.cvar95,
                ),
                efficientFrontier = response.efficientFrontier.map {
                    FrontierPoint(risk = it.risk, ret = it.ret)
                },
                correlationMatrix = response.correlationMatrix,
            ),
            status = "COMPLETED",
            computationMs = response.computationMs,
        )
        return optimizationRunRepository.save(run)
    }

    companion object {
        private const val LOOKBACK_YEARS = 5

        fun computeCagr(intrinsicValue: Double, currentPrice: Double): Double =
            (intrinsicValue / currentPrice).pow(1.0 / LOOKBACK_YEARS) - 1.0
    }
}
