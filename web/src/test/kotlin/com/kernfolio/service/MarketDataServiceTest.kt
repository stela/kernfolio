package com.kernfolio.service

import com.kernfolio.domain.CachedPrice
import com.kernfolio.domain.Instrument
import com.kernfolio.domain.Position
import com.kernfolio.marketdata.TickerMapper
import com.kernfolio.marketdata.YFinanceFetcher
import com.kernfolio.marketdata.dto.FetchPricesResponse
import com.kernfolio.marketdata.dto.PricePoint
import com.kernfolio.marketdata.dto.TickerMetadata
import com.kernfolio.marketdata.dto.TickerSearchResult
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

    @Test
    fun `searchInstruments returns empty for blank query without hitting backends`() {
        val result = service.searchInstruments("   ", 10)
        assertEquals(emptyList<TickerSuggestion>(), result)
        verify(exactly = 0) { instrumentRepository.search(any(), any()) }
        verify(exactly = 0) { yFinanceFetcher.searchTickers(any(), any()) }
    }

    @Test
    fun `searchInstruments returns only local hits when local fills the limit`() {
        every { instrumentRepository.search("app", 3) } returns listOf(
            instrument("AAPL", "Apple Inc."),
            instrument("AAP", "Advance Auto Parts"),
            instrument("APP", "Applovin"),
        )

        val result = service.searchInstruments("app", 3)
        assertEquals(listOf("AAPL", "AAP", "APP"), result.map { it.ticker })
        assertEquals(listOf("local", "local", "local"), result.map { it.source })
        verify(exactly = 0) { yFinanceFetcher.searchTickers(any(), any()) }
    }

    @Test
    fun `searchInstruments falls back to remote when local is sparse`() {
        every { instrumentRepository.search("goog", 5) } returns listOf(
            instrument("GOOG", "Alphabet Inc.")
        )
        every { instrumentRepository.findById("GOOGL") } returns Optional.empty()
        every { yFinanceFetcher.searchTickers("goog", 5) } returns listOf(
            TickerSearchResult(
                symbol = "GOOGL", shortname = "Alphabet", longname = "Alphabet Inc. Class A",
                exchange = "NASDAQ", currency = "USD",
            ),
            // Duplicate of local — must be filtered out by dedupe
            TickerSearchResult(symbol = "GOOG", shortname = "Alphabet", currency = "USD"),
        )

        val result = service.searchInstruments("goog", 5)
        assertEquals(listOf("GOOG", "GOOGL"), result.map { it.ticker })
        assertEquals(listOf("local", "remote"), result.map { it.source })
    }

    @Test
    fun `searchInstruments persists fresh remote hits as instruments rows`() {
        every { instrumentRepository.search("apv", 5) } returns emptyList()
        every { instrumentRepository.findById("APP") } returns Optional.empty()
        every { yFinanceFetcher.searchTickers("apv", 5) } returns listOf(
            TickerSearchResult(
                symbol = "APP", shortname = "AppLovin", longname = "AppLovin Corporation",
                exchange = "NMS", currency = "USD",
            ),
        )

        service.searchInstruments("apv", 5)

        verify {
            instrumentRepository.save(match<Instrument> {
                it.ticker == "APP" &&
                    it.name == "AppLovin Corporation" &&
                    it.exchange == "NMS" &&
                    it.currency == "USD" &&
                    it.isNew
            })
        }
    }

    @Test
    fun `searchInstruments does not overwrite richer existing instrument fields`() {
        val existing = Instrument(
            ticker = "AAPL",
            name = "Apple Inc.",
            exchange = "NASDAQ",
            currency = "USD",
            sector = "Technology",
            marketCapUsd = java.math.BigDecimal("3000000000000"),
        ).markNotNew()

        every { instrumentRepository.search("app", 5) } returns emptyList()
        every { instrumentRepository.findById("AAPL") } returns Optional.of(existing)
        every { yFinanceFetcher.searchTickers("app", 5) } returns listOf(
            // Remote returns a different (less rich) name for the same symbol.
            TickerSearchResult(
                symbol = "AAPL", shortname = "AAPL", longname = null,
                exchange = "NASDAQ", currency = "USD",
            ),
        )

        service.searchInstruments("app", 5)

        // No save: every field already populated with richer data.
        verify(exactly = 0) { instrumentRepository.save(any<Instrument>()) }
    }

    @Test
    fun `searchInstruments fills gaps on a partially populated existing row`() {
        val existing = Instrument(
            ticker = "FOO",
            name = null,
            exchange = null,
            currency = null,
            sector = "Consumer",  // pre-existing, must survive the merge
        ).markNotNew()

        every { instrumentRepository.search("foo", 5) } returns emptyList()
        every { instrumentRepository.findById("FOO") } returns Optional.of(existing)
        every { yFinanceFetcher.searchTickers("foo", 5) } returns listOf(
            TickerSearchResult(
                symbol = "FOO", shortname = "FOO Corp", longname = "FOO Corporation",
                exchange = "NYSE", currency = "USD",
            ),
        )

        service.searchInstruments("foo", 5)

        verify {
            instrumentRepository.save(match<Instrument> {
                it.ticker == "FOO" &&
                    it.name == "FOO Corporation" &&
                    it.exchange == "NYSE" &&
                    it.currency == "USD" &&
                    it.sector == "Consumer" &&  // preserved
                    !it.isNew
            })
        }
    }

    @Test
    fun `searchInstruments converts remote yfinance symbols to internal tickers`() {
        every { instrumentRepository.search("mex", 5) } returns emptyList()
        every { yFinanceFetcher.searchTickers("mex", 5) } returns listOf(
            TickerSearchResult(
                symbol = "GMEXICOB.MX", shortname = "Grupo Mexico",
                exchange = "BMV", currency = "MXN",
            ),
        )

        val result = service.searchInstruments("mex", 5)
        assertEquals(listOf("GMEXICOB"), result.map { it.ticker })
        assertEquals("remote", result.first().source)
    }

    @Test
    fun `searchInstruments falls back to local when remote throws`() {
        every { instrumentRepository.search("q", 5) } returns listOf(
            instrument("FOO", "Foo Inc.")
        )
        every { yFinanceFetcher.searchTickers(any(), any()) } returns emptyList()

        val result = service.searchInstruments("q", 5)
        assertEquals(listOf("FOO"), result.map { it.ticker })
    }

    private fun instrument(ticker: String, name: String) = Instrument(
        ticker = ticker,
        name = name,
        currency = "USD",
    )
}
