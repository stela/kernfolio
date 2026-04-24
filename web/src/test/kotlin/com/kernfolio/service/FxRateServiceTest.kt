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

    @Test
    fun `getLatestCrossRateInfoOrFetch normalises currency codes and short-circuits same-currency`() {
        // Lowercase or mixed-case input must not bypass the identity short-
        // circuit or produce bogus Frankfurter lookups like "EUReur".
        val info = service.getLatestCrossRateInfoOrFetch("eur", " EuR ")!!

        assertEquals(BigDecimal.ONE, info.rate)
        verify(exactly = 0) { frankfurterClient.fetchFxRates(any(), any(), any(), any()) }
        verify(exactly = 0) { cachedFxRateRepository.findLatestByCurrencyPair(any()) }
    }

    // --- ensureCoverage ---

    private fun cachedRate(pair: String, date: LocalDate) =
        CachedFxRate(currencyPair = pair, rateDate = date, rate = BigDecimal("1.1"))

    @Test
    fun `ensureCoverage is a no-op when no currencies given`() {
        service.ensureCoverage(emptyList(), LocalDate.of(2021, 1, 1), LocalDate.of(2026, 1, 1))

        verify(exactly = 0) { frankfurterClient.fetchFxRates(any(), any(), any(), any()) }
        verify(exactly = 0) { cachedFxRateRepository.findEarliestByCurrencyPair(any()) }
    }

    @Test
    fun `ensureCoverage skips EUR`() {
        service.ensureCoverage(listOf("EUR"), LocalDate.of(2021, 1, 1), LocalDate.of(2026, 1, 1))

        verify(exactly = 0) { frankfurterClient.fetchFxRates(any(), any(), any(), any()) }
        verify(exactly = 0) { cachedFxRateRepository.findEarliestByCurrencyPair(any()) }
    }

    @Test
    fun `ensureCoverage is a no-op when cache fully covers the window`() {
        val windowStart = LocalDate.of(2021, 1, 1)
        val windowEnd = LocalDate.of(2026, 1, 1)
        every { cachedFxRateRepository.findEarliestByCurrencyPair("EURUSD") } returns
            cachedRate("EURUSD", windowStart)
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns
            cachedRate("EURUSD", windowEnd)

        service.ensureCoverage(listOf("USD"), windowStart, windowEnd)

        verify(exactly = 0) { frankfurterClient.fetchFxRates(any(), any(), any(), any()) }
    }

    @Test
    fun `ensureCoverage fetches full range in one shot when cache is empty and window is under 365 days`() {
        val windowStart = LocalDate.of(2025, 10, 1)
        val windowEnd = LocalDate.of(2026, 4, 1)
        every { cachedFxRateRepository.findEarliestByCurrencyPair("EURUSD") } returns null
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns null
        every { frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), any()) } returns
            FetchFxRatesResponse(rates = emptyMap())

        service.ensureCoverage(listOf("USD"), windowStart, windowEnd)

        verify(exactly = 1) {
            frankfurterClient.fetchFxRates("EUR", listOf("USD"), windowStart, windowEnd)
        }
    }

    @Test
    fun `ensureCoverage splits a 6-year window into six chunks of 365 days`() {
        val windowStart = LocalDate.of(2020, 1, 1)
        val windowEnd = LocalDate.of(2026, 1, 1)
        every { cachedFxRateRepository.findEarliestByCurrencyPair("EURUSD") } returns null
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns null
        every { frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), any()) } returns
            FetchFxRatesResponse(rates = emptyMap())

        service.ensureCoverage(listOf("USD"), windowStart, windowEnd)

        // 2191 days / 365 = 6 full chunks + 1 remainder chunk = 7 calls
        verify(exactly = 7) {
            frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), any())
        }
    }

    @Test
    fun `ensureCoverage issues separate fetches for old-side and new-side gaps`() {
        val windowStart = LocalDate.of(2021, 1, 1)
        val windowEnd = LocalDate.of(2026, 4, 24)
        // Cache only covers a narrow middle strip, like the 14-day on-demand
        // UI fetch — gaps both before and after.
        val cachedStart = LocalDate.of(2026, 4, 9)
        val cachedEnd = LocalDate.of(2026, 4, 10)
        every { cachedFxRateRepository.findEarliestByCurrencyPair("EURUSD") } returns
            cachedRate("EURUSD", cachedStart)
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns
            cachedRate("EURUSD", cachedEnd)
        every { frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), any()) } returns
            FetchFxRatesResponse(rates = emptyMap())

        service.ensureCoverage(listOf("USD"), windowStart, windowEnd)

        // Old-side gap: 2021-01-01 → 2026-04-08. 1924 days → 6 chunks
        //   (5 × 365 + 1 × 99). Plus new-side gap: 2026-04-11 → 2026-04-24,
        //   1 chunk. Total = 7 fetches.
        verify(exactly = 7) {
            frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), any())
        }
        // Old-side gap ends the day before the cache starts.
        verify(atLeast = 1) {
            frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), cachedStart.minusDays(1))
        }
        // New-side gap starts the day after the cache ends and ends at windowEnd.
        verify(exactly = 1) {
            frankfurterClient.fetchFxRates("EUR", listOf("USD"), cachedEnd.plusDays(1), windowEnd)
        }
    }

    @Test
    fun `ensureCoverage batches currencies with identical missing ranges into one call`() {
        val windowStart = LocalDate.of(2025, 6, 1)
        val windowEnd = LocalDate.of(2025, 7, 1)
        // Both USD and JPY have empty caches → identical "missing all" gap,
        // so they should share a single multi-currency request.
        every { cachedFxRateRepository.findEarliestByCurrencyPair(any()) } returns null
        every { cachedFxRateRepository.findLatestByCurrencyPair(any()) } returns null
        every { frankfurterClient.fetchFxRates(any(), any(), any(), any()) } returns
            FetchFxRatesResponse(rates = emptyMap())

        service.ensureCoverage(listOf("USD", "JPY"), windowStart, windowEnd)

        verify(exactly = 1) {
            frankfurterClient.fetchFxRates(
                "EUR",
                match { it.toSet() == setOf("USD", "JPY") },
                windowStart,
                windowEnd,
            )
        }
    }

    @Test
    fun `ensureCoverage issues separate calls for currencies with different gaps`() {
        val windowStart = LocalDate.of(2021, 1, 1)
        val windowEnd = LocalDate.of(2025, 1, 1)
        // USD: fully cached. JPY: nothing cached.
        every { cachedFxRateRepository.findEarliestByCurrencyPair("EURUSD") } returns
            cachedRate("EURUSD", windowStart)
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns
            cachedRate("EURUSD", windowEnd)
        every { cachedFxRateRepository.findEarliestByCurrencyPair("EURJPY") } returns null
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURJPY") } returns null
        every { frankfurterClient.fetchFxRates(any(), any(), any(), any()) } returns
            FetchFxRatesResponse(rates = emptyMap())

        service.ensureCoverage(listOf("USD", "JPY"), windowStart, windowEnd)

        // Only JPY is fetched; USD is skipped entirely.
        verify(exactly = 0) {
            frankfurterClient.fetchFxRates("EUR", match { it.contains("USD") }, any(), any())
        }
        verify(atLeast = 1) {
            frankfurterClient.fetchFxRates("EUR", listOf("JPY"), any(), any())
        }
    }

    @Test
    fun `ensureCoverage upserts every returned rate`() {
        val windowStart = LocalDate.of(2025, 6, 1)
        val windowEnd = LocalDate.of(2025, 6, 3)
        every { cachedFxRateRepository.findEarliestByCurrencyPair(any()) } returns null
        every { cachedFxRateRepository.findLatestByCurrencyPair(any()) } returns null
        every { frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), any()) } returns
            FetchFxRatesResponse(
                rates = mapOf(
                    LocalDate.of(2025, 6, 2) to mapOf("USD" to BigDecimal("1.05")),
                    LocalDate.of(2025, 6, 3) to mapOf("USD" to BigDecimal("1.06")),
                )
            )

        service.ensureCoverage(listOf("USD"), windowStart, windowEnd)

        verify { cachedFxRateRepository.upsert("EURUSD", LocalDate.of(2025, 6, 2), BigDecimal("1.05")) }
        verify { cachedFxRateRepository.upsert("EURUSD", LocalDate.of(2025, 6, 3), BigDecimal("1.06")) }
    }

    @Test
    fun `ensureCoverage normalises lowercase currency codes`() {
        every { cachedFxRateRepository.findEarliestByCurrencyPair("EURUSD") } returns null
        every { cachedFxRateRepository.findLatestByCurrencyPair("EURUSD") } returns null
        every { frankfurterClient.fetchFxRates(any(), any(), any(), any()) } returns
            FetchFxRatesResponse(rates = emptyMap())

        service.ensureCoverage(
            listOf("usd"), LocalDate.of(2025, 6, 1), LocalDate.of(2025, 6, 2)
        )

        verify {
            frankfurterClient.fetchFxRates("EUR", listOf("USD"), any(), any())
        }
    }
}
