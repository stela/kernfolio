package com.kernfolio.service

import com.kernfolio.domain.CachedPrice
import com.kernfolio.domain.Instrument
import com.kernfolio.domain.Position
import com.kernfolio.marketdata.TickerMapper
import com.kernfolio.marketdata.YFinanceFetcher
import com.kernfolio.marketdata.dto.FetchPricesResponse
import com.kernfolio.marketdata.dto.PricePoint
import com.kernfolio.marketdata.dto.TickerMetadata
import com.kernfolio.repository.CachedPriceRepository
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.PositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional
import java.util.UUID

class MarketDataServiceTest {

    private val yFinanceFetcher = mockk<YFinanceFetcher>()
    private val tickerMapper = TickerMapper()
    private val cachedPriceRepository = mockk<CachedPriceRepository>(relaxed = true)
    private val instrumentRepository = mockk<InstrumentRepository> {
        every { save(any<Instrument>()) } answers { firstArg() }
    }
    private val positionRepository = mockk<PositionRepository>()
    private val fxRateService = mockk<FxRateService>(relaxed = true)

    private val service = MarketDataService(
        yFinanceFetcher, tickerMapper, cachedPriceRepository,
        instrumentRepository, positionRepository, fxRateService,
    )

    private fun position(ticker: String, type: String = "EQUITY") = Position(
        id = UUID.randomUUID(),
        portfolioId = UUID.randomUUID(),
        ticker = ticker,
        positionType = type,
        currency = "USD",
        weightPct = BigDecimal("0.05"),
    )

    @Test
    fun `fetchAndCachePrices calls fetcher with yfinance tickers`() {
        every { cachedPriceRepository.findMaxDateByTicker(any()) } returns null
        every { instrumentRepository.findById(any<String>()) } returns Optional.empty()
        every { yFinanceFetcher.fetchPrices(any(), any(), any()) } returns FetchPricesResponse(
            prices = emptyMap(), metadata = emptyMap(), errors = emptyMap(),
        )

        service.fetchAndCachePrices(listOf("GOOG", "GMEXICOB"))

        verify {
            yFinanceFetcher.fetchPrices(
                match { it.contains("GOOG") && it.contains("GMEXICOB.MX") },
                any(), any(),
            )
        }
    }

    @Test
    fun `fetchAndCachePrices upserts prices and updates instruments`() {
        val date = LocalDate.of(2025, 6, 15)
        every { cachedPriceRepository.findMaxDateByTicker(any()) } returns null
        every { instrumentRepository.findById(any<String>()) } returns Optional.empty()
        every { yFinanceFetcher.fetchPrices(any(), any(), any()) } returns FetchPricesResponse(
            prices = mapOf(
                "GOOG" to listOf(PricePoint(date = date, close = BigDecimal("180.50")))
            ),
            metadata = mapOf(
                "GOOG" to TickerMetadata(
                    currency = "USD", name = "Alphabet Inc.",
                    marketCap = BigDecimal("1850000000000"), sector = "Technology",
                )
            ),
            errors = emptyMap(),
        )

        service.fetchAndCachePrices(listOf("GOOG"))

        verify { cachedPriceRepository.upsert("GOOG", date, BigDecimal("180.50"), "USD") }
        verify { instrumentRepository.save(match<Instrument> { it.ticker == "GOOG" && it.currency == "USD" }) }
    }

    @Test
    fun `fetchAndCachePrices uses incremental start date per ticker`() {
        val maxDate = LocalDate.of(2025, 12, 1)
        every { cachedPriceRepository.findMaxDateByTicker("GOOG") } returns maxDate
        every { cachedPriceRepository.findMaxDateByTicker("AMZN") } returns null
        every { instrumentRepository.findById(any<String>()) } returns Optional.empty()
        every { yFinanceFetcher.fetchPrices(any(), any(), any()) } returns FetchPricesResponse(
            prices = emptyMap(), metadata = emptyMap(), errors = emptyMap(),
        )

        service.fetchAndCachePrices(listOf("GOOG", "AMZN"))

        // Global start should be 5 years ago (from AMZN with no cached data)
        verify {
            yFinanceFetcher.fetchPrices(
                any(),
                match { it.isBefore(maxDate) },
                any(),
            )
        }
    }

    @Test
    fun `fetchAndCachePrices skips when all prices up to date`() {
        every { cachedPriceRepository.findMaxDateByTicker(any()) } returns LocalDate.now()
        every { yFinanceFetcher.fetchPrices(any(), any(), any()) } returns FetchPricesResponse(
            prices = emptyMap(), metadata = emptyMap(), errors = emptyMap(),
        )

        service.fetchAndCachePrices(listOf("GOOG"))

        verify(exactly = 0) { yFinanceFetcher.fetchPrices(any(), any(), any()) }
    }

    @Test
    fun `fetchAndCachePrices handles errors without throwing`() {
        every { cachedPriceRepository.findMaxDateByTicker(any()) } returns null
        every { instrumentRepository.findById(any<String>()) } returns Optional.empty()
        every { yFinanceFetcher.fetchPrices(any(), any(), any()) } returns FetchPricesResponse(
            prices = mapOf(
                "GOOG" to listOf(
                    PricePoint(date = LocalDate.of(2025, 6, 15), close = BigDecimal("180.50"))
                )
            ),
            metadata = mapOf(
                "GOOG" to TickerMetadata(currency = "USD", name = "Alphabet", marketCap = null, sector = null)
            ),
            errors = mapOf("INVALID" to "No data found for ticker INVALID"),
        )

        service.fetchAndCachePrices(listOf("GOOG", "INVALID"))

        verify { cachedPriceRepository.upsert("GOOG", any(), any(), any()) }
    }

    @Test
    fun `refreshAllPrices filters out CASH positions`() {
        every { positionRepository.findAll() } returns listOf(
            position("GOOG", "EQUITY"),
            position("CASH.USD", "CASH"),
        )
        every { cachedPriceRepository.findMaxDateByTicker(any()) } returns null
        every { instrumentRepository.findById(any<String>()) } returns Optional.empty()
        every { yFinanceFetcher.fetchPrices(any(), any(), any()) } returns FetchPricesResponse(
            prices = emptyMap(), metadata = emptyMap(), errors = emptyMap(),
        )

        service.refreshAllPrices()

        verify {
            yFinanceFetcher.fetchPrices(
                match { it.size == 1 && it.contains("GOOG") },
                any(), any(),
            )
        }
    }

    @Test
    fun `refreshAllPrices skips when no equity positions`() {
        every { positionRepository.findAll() } returns listOf(
            position("CASH.USD", "CASH"),
        )

        service.refreshAllPrices()

        verify(exactly = 0) { yFinanceFetcher.fetchPrices(any(), any(), any()) }
    }

    @Test
    fun `getLatestPrices delegates to repository`() {
        val price = CachedPrice(
            ticker = "GOOG", priceDate = LocalDate.now(),
            closePrice = BigDecimal("180.50"), currency = "USD",
        )
        every { cachedPriceRepository.findLatestByTicker("GOOG") } returns price
        every { cachedPriceRepository.findLatestByTicker("MISSING") } returns null

        val result = service.getLatestPrices(listOf("GOOG", "MISSING"))

        assertEquals(price, result["GOOG"])
        assertNull(result["MISSING"])
    }

    @Test
    fun `updateInstruments marks existing instruments as not new`() {
        val existing = Instrument(ticker = "GOOG", name = "Old Name", currency = "USD")
        every { instrumentRepository.findById("GOOG") } returns Optional.of(existing)

        every { cachedPriceRepository.findMaxDateByTicker(any()) } returns null
        every { yFinanceFetcher.fetchPrices(any(), any(), any()) } returns FetchPricesResponse(
            prices = mapOf(
                "GOOG" to listOf(
                    PricePoint(date = LocalDate.of(2025, 6, 15), close = BigDecimal("180.50"))
                )
            ),
            metadata = mapOf(
                "GOOG" to TickerMetadata(currency = "USD", name = "Alphabet Inc.", marketCap = null, sector = "Technology")
            ),
            errors = emptyMap(),
        )

        service.fetchAndCachePrices(listOf("GOOG"))

        verify {
            instrumentRepository.save(match<Instrument> {
                it.ticker == "GOOG" && it.name == "Alphabet Inc." && !it.isNew
            })
        }
    }
}
