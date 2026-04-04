package com.kernfolio.service

import com.kernfolio.config.MarketDataProperties
import com.kernfolio.domain.CachedFxRate
import com.kernfolio.domain.Position
import com.kernfolio.marketdata.FrankfurterClient
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
}
