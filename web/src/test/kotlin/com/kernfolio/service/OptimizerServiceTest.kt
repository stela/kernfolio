package com.kernfolio.service

import com.kernfolio.domain.CachedPrice
import com.kernfolio.domain.Instrument
import com.kernfolio.domain.OptimizationRun
import com.kernfolio.domain.Portfolio
import com.kernfolio.domain.Position
import com.kernfolio.marketdata.MarketDataException
import com.kernfolio.marketdata.TickerMapper
import com.kernfolio.repository.CachedPriceRepository
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.OptimizationRunRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class OptimizerServiceTest {

    private val portfolioService = mockk<PortfolioService>()
    private val cachedPriceRepository = mockk<CachedPriceRepository>()
    private val instrumentRepository = mockk<InstrumentRepository>()
    private val currencyConversionService = mockk<CurrencyConversionService>()
    private val fxRateService = mockk<FxRateService>(relaxed = true)
    private val tickerMapper = TickerMapper()
    private val optimizationRunRepository = mockk<OptimizationRunRepository> {
        every { save(any<OptimizationRun>()) } answers { firstArg() }
    }
    private val optimizerWebClient = mockk<WebClient>()
    private val objectMapper = mockk<ObjectMapper>()

    private val service = OptimizerService(
        portfolioService, cachedPriceRepository, instrumentRepository,
        currencyConversionService, fxRateService, tickerMapper, optimizationRunRepository,
        optimizerWebClient, objectMapper,
    )

    private val userId = UUID.randomUUID()
    private val portfolioId = UUID.randomUUID()

    private val portfolio = Portfolio(
        id = portfolioId,
        userId = userId,
        name = "Test Portfolio",
        baseCurrency = "EUR",
    )

    private fun position(
        ticker: String,
        currency: String = "USD",
        expectedReturn: BigDecimal? = null,
        returnStddev: BigDecimal? = null,
        sector: String? = "Technology",
        type: String = "EQUITY",
    ) = Position(
        id = UUID.randomUUID(),
        portfolioId = portfolioId,
        ticker = ticker,
        currency = currency,
        weightPct = BigDecimal("0.05"),
        expectedReturn = expectedReturn,
        returnStddev = returnStddev,
        sector = sector,
        positionType = type,
    )

    private fun cachedPrice(ticker: String, date: LocalDate, price: BigDecimal, currency: String = "USD") =
        CachedPrice(ticker = ticker, priceDate = date, closePrice = price, currency = currency)

    // Stubs the optimizer WebClient chain to return a trivial successful
    // response. Individual tests that need to capture the request body
    // should still do it inline (slot<Any>()), but tests that only care
    // that `optimize` completes past the HTTP call can use this.
    private fun stubOptimizerOk() {
        val requestSpec = mockk<WebClient.RequestBodyUriSpec>()
        val requestBodySpec = mockk<WebClient.RequestBodySpec>()
        val responseSpec = mockk<WebClient.ResponseSpec>()
        every { optimizerWebClient.post() } returns requestSpec
        every { requestSpec.uri("/optimize") } returns requestBodySpec
        every { requestBodySpec.bodyValue(any()) } returns requestBodySpec
        every { requestBodySpec.retrieve() } returns responseSpec
        every { responseSpec.bodyToMono(any<Class<*>>()) } returns mockk {
            every { block() } returns com.kernfolio.dto.OptimizeResponseDto(
                weights = emptyMap(),
                metrics = com.kernfolio.dto.OptimizeMetricsDto(0.09, 0.17, 0.35, -0.03),
                efficientFrontier = emptyList(),
                correlationMatrix = emptyMap(),
                computationMs = 100,
            )
        }
    }

    @Nested
    inner class PayloadAssembly {

        @Test
        fun `sends the user's return and std-dev as the view, untouched`() {
            val posWithIV = position("GOOG", expectedReturn = BigDecimal("0.1250"), returnStddev = BigDecimal("0.3000"))
            val posWithoutIV = position("AMZN")
            // Can't be saved through PortfolioService, but must not become half a view if it exists.
            val posPartialIV = position("NVDA", expectedReturn = BigDecimal("0.2000"))

            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns
                listOf(posWithIV, posWithoutIV, posPartialIV)

            val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
            val prices = listOf("GOOG", "AMZN", "NVDA").flatMap { ticker ->
                dates.map { date -> cachedPrice(ticker, date, BigDecimal("100")) }
            }
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { currencyConversionService.convertTo(any(), any(), any()) } answers {
                (firstArg<List<CachedPrice>>()).map { it.priceDate to it.closePrice }
            }
            every { cachedPriceRepository.findLatestByTicker("GOOG") } returns
                cachedPrice("GOOG", LocalDate.now(), BigDecimal("100"))
            every { cachedPriceRepository.findLatestByTicker("AMZN") } returns
                cachedPrice("AMZN", LocalDate.now(), BigDecimal("100"))
            every { cachedPriceRepository.findLatestByTicker("NVDA") } returns
                cachedPrice("NVDA", LocalDate.now(), BigDecimal("100"))
            every { instrumentRepository.findByTickers(any()) } returns listOf(
                Instrument(ticker = "GOOG", currency = "USD", marketCapUsd = BigDecimal("1850000000000")),
                Instrument(ticker = "AMZN", currency = "USD", marketCapUsd = BigDecimal("2100000000000")),
                Instrument(ticker = "NVDA", currency = "USD", marketCapUsd = BigDecimal("3000000000000")),
            )

            // Capture the request sent to the optimizer
            val requestSlot = slot<Any>()
            val requestSpec = mockk<WebClient.RequestBodyUriSpec>()
            val requestBodySpec = mockk<WebClient.RequestBodySpec>()
            val responseSpec = mockk<WebClient.ResponseSpec>()
            every { optimizerWebClient.post() } returns requestSpec
            every { requestSpec.uri("/optimize") } returns requestBodySpec
            every { requestBodySpec.bodyValue(capture(requestSlot)) } returns requestBodySpec
            every { requestBodySpec.retrieve() } returns responseSpec
            every { responseSpec.bodyToMono(any<Class<*>>()) } returns mockk {
                every { block() } returns com.kernfolio.dto.OptimizeResponseDto(
                    weights = mapOf("GOOG" to 0.4, "AMZN" to 0.3, "NVDA" to 0.3),
                    metrics = com.kernfolio.dto.OptimizeMetricsDto(0.09, 0.17, 0.35, -0.03),
                    efficientFrontier = emptyList(),
                    correlationMatrix = emptyMap(),
                    computationMs = 100,
                )
            }

            service.optimize(portfolioId, userId, "black_litterman")

            val request = requestSlot.captured as com.kernfolio.dto.OptimizeRequestDto
            // Only GOOG has both a return and a std-dev
            assertEquals(mapOf("GOOG" to 0.125), request.views)
            assertEquals(mapOf("GOOG" to 0.30), request.viewStddevs)
        }

        @Test
        fun `excludes CASH positions`() {
            val equity = position("GOOG")
            val cash = position("CASH.USD", type = "CASH")

            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(equity, cash)

            val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
            val prices = dates.map { date -> cachedPrice("GOOG", date, BigDecimal("100")) }
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { currencyConversionService.convertTo(any(), any(), any()) } answers {
                (firstArg<List<CachedPrice>>()).map { it.priceDate to it.closePrice }
            }
            every { cachedPriceRepository.findLatestByTicker(any()) } returns null
            every { instrumentRepository.findByTickers(any()) } returns listOf(
                Instrument(ticker = "GOOG", currency = "USD", marketCapUsd = BigDecimal("1850000000000")),
            )

            val requestSlot = slot<Any>()
            val requestSpec = mockk<WebClient.RequestBodyUriSpec>()
            val requestBodySpec = mockk<WebClient.RequestBodySpec>()
            val responseSpec = mockk<WebClient.ResponseSpec>()
            every { optimizerWebClient.post() } returns requestSpec
            every { requestSpec.uri("/optimize") } returns requestBodySpec
            every { requestBodySpec.bodyValue(capture(requestSlot)) } returns requestBodySpec
            every { requestBodySpec.retrieve() } returns responseSpec
            every { responseSpec.bodyToMono(any<Class<*>>()) } returns mockk {
                every { block() } returns com.kernfolio.dto.OptimizeResponseDto(
                    weights = mapOf("GOOG" to 1.0),
                    metrics = com.kernfolio.dto.OptimizeMetricsDto(0.09, 0.17, 0.35, -0.03),
                    efficientFrontier = emptyList(),
                    correlationMatrix = emptyMap(),
                    computationMs = 100,
                )
            }

            service.optimize(portfolioId, userId, "black_litterman")

            val request = requestSlot.captured as com.kernfolio.dto.OptimizeRequestDto
            // Only GOOG, not CASH.USD
            assertTrue(request.prices.containsKey("GOOG"))
            assertFalse(request.prices.containsKey("CASH.USD"))
            assertEquals(1, request.sectors.size)
        }

        @Test
        fun `uses yfinance tickers in request`() {
            val pos = position("GMEXICOB", currency = "MXN")

            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(pos)

            val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
            val prices = dates.map { date -> cachedPrice("GMEXICOB", date, BigDecimal("50"), "MXN") }
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { currencyConversionService.convertTo(any(), eq("MXN"), eq("EUR")) } answers {
                (firstArg<List<CachedPrice>>()).map { it.priceDate to it.closePrice }
            }
            every { cachedPriceRepository.findLatestByTicker(any()) } returns null
            every { instrumentRepository.findByTickers(any()) } returns listOf(
                Instrument(ticker = "GMEXICOB", currency = "MXN", marketCapUsd = BigDecimal("5000000000")),
            )

            val requestSlot = slot<Any>()
            val requestSpec = mockk<WebClient.RequestBodyUriSpec>()
            val requestBodySpec = mockk<WebClient.RequestBodySpec>()
            val responseSpec = mockk<WebClient.ResponseSpec>()
            every { optimizerWebClient.post() } returns requestSpec
            every { requestSpec.uri("/optimize") } returns requestBodySpec
            every { requestBodySpec.bodyValue(capture(requestSlot)) } returns requestBodySpec
            every { requestBodySpec.retrieve() } returns responseSpec
            every { responseSpec.bodyToMono(any<Class<*>>()) } returns mockk {
                every { block() } returns com.kernfolio.dto.OptimizeResponseDto(
                    weights = mapOf("GMEXICOB.MX" to 1.0),
                    metrics = com.kernfolio.dto.OptimizeMetricsDto(0.09, 0.17, 0.35, -0.03),
                    efficientFrontier = emptyList(),
                    correlationMatrix = emptyMap(),
                    computationMs = 100,
                )
            }

            val result = service.optimize(portfolioId, userId, "black_litterman")

            val request = requestSlot.captured as com.kernfolio.dto.OptimizeRequestDto
            assertTrue(request.prices.containsKey("GMEXICOB.MX"))
            assertFalse(request.prices.containsKey("GMEXICOB"))
            // Result weights should be mapped back to internal tickers
            assertTrue(result.results.optimizedWeights.containsKey("GMEXICOB"))
        }

        @Test
        fun `uppercases algorithm at the wire while keeping stored value lowercase`() {
            // Python optimizer compares request.algorithm against
            // "BLACK_LITTERMAN" literally. The HTML form and the DB-stored
            // OptimizationRun.algorithm are lowercase. Normalise only at the
            // wire so the UI/DB can stay lowercase without the Python layer
            // rejecting the request as "Unsupported algorithm".
            val pos = position("GOOG", currency = "USD")
            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(pos)
            val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
            val prices = dates.map { cachedPrice("GOOG", it, BigDecimal("100")) }
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { currencyConversionService.convertTo(any(), any(), any()) } answers {
                (firstArg<List<com.kernfolio.domain.CachedPrice>>()).map { it.priceDate to it.closePrice }
            }
            every { cachedPriceRepository.findLatestByTicker(any()) } returns null
            every { instrumentRepository.findByTickers(any()) } returns listOf(
                Instrument(ticker = "GOOG", currency = "USD", marketCapUsd = BigDecimal("1850000000000")),
            )

            val requestSlot = slot<Any>()
            val requestSpec = mockk<WebClient.RequestBodyUriSpec>()
            val requestBodySpec = mockk<WebClient.RequestBodySpec>()
            val responseSpec = mockk<WebClient.ResponseSpec>()
            every { optimizerWebClient.post() } returns requestSpec
            every { requestSpec.uri("/optimize") } returns requestBodySpec
            every { requestBodySpec.bodyValue(capture(requestSlot)) } returns requestBodySpec
            every { requestBodySpec.retrieve() } returns responseSpec
            every { responseSpec.bodyToMono(any<Class<*>>()) } returns mockk {
                every { block() } returns com.kernfolio.dto.OptimizeResponseDto(
                    weights = mapOf("GOOG" to 1.0),
                    metrics = com.kernfolio.dto.OptimizeMetricsDto(0.09, 0.17, 0.35, -0.03),
                    efficientFrontier = emptyList(),
                    correlationMatrix = emptyMap(),
                    computationMs = 100,
                )
            }

            val result = service.optimize(portfolioId, userId, "black_litterman")

            val request = requestSlot.captured as com.kernfolio.dto.OptimizeRequestDto
            assertEquals("BLACK_LITTERMAN", request.algorithm)
            // Stored algorithm stays lowercase — DB / UI convention.
            assertEquals("black_litterman", result.algorithm)
        }
    }

    @Nested
    inner class FxBackfill {

        @Test
        fun `triggers FX backfill for every non-base currency present in prices`() {
            // Regression test for: optimize used to fail with
            //   "No FX rate available for USD on or before 2021-04-26"
            // because nothing backfilled 5-year FX history before calling
            // CurrencyConversionService. Now OptimizerService must ensure
            // coverage first.
            val usdPos = position("GOOG", currency = "USD")
            val sekPos = position("VOLVO-B", currency = "SEK")

            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns
                listOf(usdPos, sekPos)

            val dates = listOf(LocalDate.of(2021, 1, 1), LocalDate.of(2026, 1, 1))
            val prices = listOf(
                cachedPrice("GOOG", dates[0], BigDecimal("100"), "USD"),
                cachedPrice("GOOG", dates[1], BigDecimal("200"), "USD"),
                cachedPrice("VOLVO-B", dates[0], BigDecimal("250"), "SEK"),
                cachedPrice("VOLVO-B", dates[1], BigDecimal("300"), "SEK"),
            )
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { currencyConversionService.convertTo(any(), any(), any()) } answers {
                (firstArg<List<com.kernfolio.domain.CachedPrice>>()).map { it.priceDate to it.closePrice }
            }
            every { cachedPriceRepository.findLatestByTicker(any()) } returns null
            every { instrumentRepository.findByTickers(any()) } returns listOf(
                Instrument(ticker = "GOOG", currency = "USD"),
                Instrument(ticker = "VOLVO-B", currency = "SEK"),
            )

            stubOptimizerOk()

            service.optimize(portfolioId, userId, "black_litterman")

            // ensureCoverage must be called with both currencies before any
            // conversion happens, so the conversion below can't fail on
            // missing FX.
            io.mockk.verify {
                fxRateService.ensureCoverage(
                    match { it.toSet() == setOf("USD", "SEK") },
                    any(),
                    any(),
                )
            }
        }

        @Test
        fun `includes non-EUR base currency in backfill set for cross-leg conversion`() {
            // SEK-based portfolio holding USD: convertTo goes USD → EUR → SEK,
            // so BOTH EUR/USD and EUR/SEK need coverage. The base currency
            // itself must therefore be part of the ensureCoverage call.
            val sekPortfolio = portfolio.copy(baseCurrency = "SEK")
            val usdPos = position("GOOG", currency = "USD")

            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns sekPortfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(usdPos)

            val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
            val prices = dates.map { cachedPrice("GOOG", it, BigDecimal("100"), "USD") }
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { currencyConversionService.convertTo(any(), any(), any()) } answers {
                (firstArg<List<com.kernfolio.domain.CachedPrice>>()).map { it.priceDate to it.closePrice }
            }
            every { cachedPriceRepository.findLatestByTicker(any()) } returns null
            every { instrumentRepository.findByTickers(any()) } returns listOf(
                Instrument(ticker = "GOOG", currency = "USD"),
            )

            stubOptimizerOk()

            service.optimize(portfolioId, userId, "black_litterman")

            io.mockk.verify {
                fxRateService.ensureCoverage(
                    match { it.toSet() == setOf("USD", "SEK") },
                    any(),
                    any(),
                )
            }
        }

        @Test
        fun `wraps backfill failure in OptimizationException with underlying cause`() {
            // Consistent with other pre-optimizer-call failures (e.g. "no
            // price data available"): the exception surfaces via the
            // optimize-error partial; no FAILED run is persisted because the
            // existing try/catch only wraps the HTTP call. Narrow scope.
            val pos = position("GOOG", currency = "USD")
            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(pos)
            val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
            val prices = dates.map { cachedPrice("GOOG", it, BigDecimal("100"), "USD") }
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { fxRateService.ensureCoverage(any(), any(), any()) } throws
                MarketDataException("frankfurter unreachable")

            val ex = assertThrows<OptimizationException> {
                service.optimize(portfolioId, userId, "black_litterman")
            }
            assertTrue(ex.message!!.contains("FX"), "message should mention FX: ${ex.message}")
            assertTrue(ex.cause is MarketDataException)
        }
    }

    @Nested
    inner class CurrencyConversionInOptimizer {

        @Test
        fun `converts prices to portfolio base currency`() {
            val pos = position("GOOG", currency = "USD")
            val usdPortfolio = portfolio.copy(baseCurrency = "USD")

            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns usdPortfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(pos)

            val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
            val prices = dates.map { date -> cachedPrice("GOOG", date, BigDecimal("180"), "USD") }
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { currencyConversionService.convertTo(any(), eq("USD"), eq("USD")) } answers {
                (firstArg<List<CachedPrice>>()).map { it.priceDate to it.closePrice }
            }
            every { cachedPriceRepository.findLatestByTicker(any()) } returns null
            every { instrumentRepository.findByTickers(any()) } returns listOf(
                Instrument(ticker = "GOOG", currency = "USD", marketCapUsd = BigDecimal("1850000000000")),
            )

            val requestSpec = mockk<WebClient.RequestBodyUriSpec>()
            val requestBodySpec = mockk<WebClient.RequestBodySpec>()
            val responseSpec = mockk<WebClient.ResponseSpec>()
            every { optimizerWebClient.post() } returns requestSpec
            every { requestSpec.uri("/optimize") } returns requestBodySpec
            every { requestBodySpec.bodyValue(any()) } returns requestBodySpec
            every { requestBodySpec.retrieve() } returns responseSpec
            every { responseSpec.bodyToMono(any<Class<*>>()) } returns mockk {
                every { block() } returns com.kernfolio.dto.OptimizeResponseDto(
                    weights = mapOf("GOOG" to 1.0),
                    metrics = com.kernfolio.dto.OptimizeMetricsDto(0.09, 0.17, 0.35, -0.03),
                    efficientFrontier = emptyList(),
                    correlationMatrix = emptyMap(),
                    computationMs = 100,
                )
            }

            service.optimize(portfolioId, userId, "black_litterman")

            verify { currencyConversionService.convertTo(any(), "USD", "USD") }
        }
    }

    @Nested
    inner class ErrorHandling {

        @Test
        fun `throws when no equity positions`() {
            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(
                position("CASH.USD", type = "CASH"),
            )

            assertThrows<OptimizationException> {
                service.optimize(portfolioId, userId, "black_litterman")
            }
        }

        @Test
        fun `throws when no price data available`() {
            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(
                position("GOOG"),
            )
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns emptyList()

            assertThrows<OptimizationException> {
                service.optimize(portfolioId, userId, "black_litterman")
            }
        }

        @Test
        fun `saves FAILED run on optimizer error`() {
            every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
            every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(
                position("GOOG"),
            )

            val dates = listOf(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 2))
            val prices = dates.map { date -> cachedPrice("GOOG", date, BigDecimal("100")) }
            every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
            every { currencyConversionService.convertTo(any(), any(), any()) } answers {
                (firstArg<List<CachedPrice>>()).map { it.priceDate to it.closePrice }
            }
            every { cachedPriceRepository.findLatestByTicker(any()) } returns null
            every { instrumentRepository.findByTickers(any()) } returns listOf(
                Instrument(ticker = "GOOG", currency = "USD", marketCapUsd = BigDecimal("1850000000000")),
            )

            val requestSpec = mockk<WebClient.RequestBodyUriSpec>()
            val requestBodySpec = mockk<WebClient.RequestBodySpec>()
            every { optimizerWebClient.post() } returns requestSpec
            every { requestSpec.uri("/optimize") } returns requestBodySpec
            every { requestBodySpec.bodyValue(any()) } returns requestBodySpec
            every { requestBodySpec.retrieve() } throws RuntimeException("Connection refused")

            assertThrows<OptimizationException> {
                service.optimize(portfolioId, userId, "black_litterman")
            }

            verify {
                optimizationRunRepository.save(match<OptimizationRun> { it.status == "FAILED" })
            }
        }
    }
}
