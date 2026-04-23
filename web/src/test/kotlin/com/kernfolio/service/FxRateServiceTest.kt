package com.kernfolio.service

import com.kernfolio.config.MarketDataProperties
import com.kernfolio.domain.CachedFxRate
import com.kernfolio.domain.Position
import com.kernfolio.marketdata.FrankfurterClient
import com.kernfolio.marketdata.MarketDataException
import com.kernfolio.marketdata.dto.FetchFxRatesResponse
import com.kernfolio.repository.CachedFxRateRepository
import com.kernfolio.repository.PositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class FxRateServiceTest {

    private val frankfurterClient = mockk<FrankfurterClient>()
    private val cachedFxRateRepository = mockk<CachedFxRateRepository>(relaxed = true)
    private val positionRepository = mockk<PositionRepository>()
    private val marketDataProperties = MarketDataProperties(
        defaultCurrencies = listOf("USD", "CAD", "JPY", "GBP", "MXN", "HKD"),
    )

    private val service = FxRateService(
        frankfurterClient, cachedFxRateRepository, positionRepository, marketDataProperties
    )

    private fun position(currency: String) = Position(
        id = UUID.randomUUID(),
        portfolioId = UUID.randomUUID(),
        ticker = "TEST",
        currency = currency,
        weightPct = BigDecimal("0.05"),
    )

    @Test
    fun `refreshAllFxRates collects currencies from positions and defaults`() {
        every { positionRepository.findAll() } returns listOf(
            position("USD"), position("JPY"), position("CHF"),
        )
        every { cachedFxRateRepository.findLatestByCurrencyPair(any()) } returns null
        every { frankfurterClient.fetchFxRates(any(), any(), any(), any()) } returns
            FetchFxRatesResponse(rates = emptyMap())

        service.refreshAllFxRates()

        verify {
            frankfurterClient.fetchFxRates(
                "EUR",
                match { it.containsAll(listOf("USD", "CAD", "JPY", "GBP", "MXN", "HKD", "CHF")) },
                any(),
                any(),
            )
        }
    }

    @Test
    fun `refreshAllFxRates upserts each rate`() {
        every { positionRepository.findAll() } returns emptyList()
        every { cachedFxRateRepository.findLatestByCurrencyPair(any()) } returns null

        val date = LocalDate.of(2025, 1, 15)
        every { frankfurterClient.fetchFxRates(any(), any(), any(), any()) } returns
            FetchFxRatesResponse(
                rates = mapOf(
                    date to mapOf("USD" to BigDecimal("1.19"), "JPY" to BigDecimal("130.0"))
                )
            )

        service.refreshAllFxRates()

        verify { cachedFxRateRepository.upsert("EURUSD", date, BigDecimal("1.19")) }
        verify { cachedFxRateRepository.upsert("EURJPY", date, BigDecimal("130.0")) }
    }

    @Test
    fun `refreshAllFxRates uses incremental start date`() {
        every { positionRepository.findAll() } returns emptyList()
        val latestDate = LocalDate.of(2025, 12, 1)
        every { cachedFxRateRepository.findLatestByCurrencyPair(any()) } returns
            CachedFxRate(currencyPair = "EURUSD", rateDate = latestDate, rate = BigDecimal("1.19"))
        every { frankfurterClient.fetchFxRates(any(), any(), any(), any()) } returns
            FetchFxRatesResponse(rates = emptyMap())

        service.refreshAllFxRates()

        verify {
            frankfurterClient.fetchFxRates("EUR", any(), latestDate.plusDays(1), any())
        }
    }

    @Test
    fun `getLatestRate returns ONE for EUR`() {
        assertEquals(BigDecimal.ONE, service.getLatestRate("EUR"))
    }

    @Test
    fun `getLatestRate delegates to repository for non-EUR`() {
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns
            CachedFxRate(currencyPair = "EURUSD", rateDate = LocalDate.now(), rate = BigDecimal("1.19"))

        assertEquals(BigDecimal("1.19"), service.getLatestRate("USD"))
    }

    @Test
    fun `getLatestRate returns null when no cached rate`() {
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURCHF") } returns null
        assertNull(service.getLatestRate("CHF"))
    }

    @Test
    fun `getRatesForDateRange returns empty for EUR`() {
        val result = service.getRatesForDateRange("EUR", LocalDate.now().minusDays(5), LocalDate.now())
        assertEquals(emptyList<CachedFxRate>(), result)
    }

    @Test
    fun `getLatestCrossRateInfoOrFetch returns cached rate with timestamps and without fetching`() {
        val rateDate = LocalDate.of(2026, 4, 20)
        val fetched = Instant.parse("2026-04-20T14:23:00Z")
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns
            CachedFxRate("EURUSD", rateDate, BigDecimal("1.08"), fetched)

        val info = service.getLatestCrossRateInfoOrFetch("EUR", "USD")!!

        assertEquals(BigDecimal("1.08000000"), info.rate)
        assertEquals(rateDate, info.asOf)
        assertEquals(fetched, info.fetchedAt)
        verify(exactly = 0) { frankfurterClient.fetchFxRates(any(), any(), any(), any()) }
    }

    @Test
    fun `getLatestCrossRateInfoOrFetch fetches from Frankfurter on cache miss and returns info`() {
        val date = LocalDate.of(2026, 4, 18)
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returnsMany listOf(
            null,
            CachedFxRate("EURUSD", date, BigDecimal("1.08"), Instant.parse("2026-04-20T10:00:00Z")),
        )
        every { frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), any()) } returns
            FetchFxRatesResponse(rates = mapOf(date to mapOf("USD" to BigDecimal("1.08"))))

        val info = service.getLatestCrossRateInfoOrFetch("EUR", "USD")!!

        assertEquals(BigDecimal("1.08000000"), info.rate)
        assertEquals(date, info.asOf)
        verify { cachedFxRateRepository.upsert("EURUSD", date, BigDecimal("1.08")) }
    }

    @Test
    fun `getLatestCrossRateInfoOrFetch returns null and swallows MarketDataException on fetch failure`() {
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns null
        every { frankfurterClient.fetchFxRates(any(), any(), any(), any()) } throws
            MarketDataException("frankfurter unreachable")

        assertNull(service.getLatestCrossRateInfoOrFetch("EUR", "USD"))
    }

    @Test
    fun `getLatestCrossRateInfoOrFetch picks oldest timestamps across two-leg cross`() {
        val usdDate = LocalDate.of(2026, 4, 18)
        val jpyDate = LocalDate.of(2026, 4, 20)
        val usdFetched = Instant.parse("2026-04-20T08:00:00Z")
        val jpyFetched = Instant.parse("2026-04-20T12:00:00Z")
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns
            CachedFxRate("EURUSD", usdDate, BigDecimal("1.08"), usdFetched)
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURJPY") } returns
            CachedFxRate("EURJPY", jpyDate, BigDecimal("162.50"), jpyFetched)

        val info = service.getLatestCrossRateInfoOrFetch("USD", "JPY")!!

        // USD→JPY cross = EUR/JPY ÷ EUR/USD = 162.50 / 1.08 ≈ 150.46
        assertEquals(BigDecimal("150.46296296"), info.rate)
        // Oldest asOf and oldest fetchedAt win (conservative "as of").
        assertEquals(usdDate, info.asOf)
        assertEquals(usdFetched, info.fetchedAt)
    }

    @Test
    fun `getLatestCrossRateInfoOrFetch treats null fetchedAt as epoch`() {
        val date = LocalDate.of(2026, 4, 18)
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns
            CachedFxRate("EURUSD", date, BigDecimal("1.08"), fetchedAt = null)

        val info = service.getLatestCrossRateInfoOrFetch("EUR", "USD")!!

        assertEquals(Instant.EPOCH, info.fetchedAt)
    }
}
