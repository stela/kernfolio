package com.kernfolio.service

import com.kernfolio.domain.CachedFxRate
import com.kernfolio.domain.CachedPrice
import com.kernfolio.marketdata.MarketDataException
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

class CurrencyConversionServiceTest {

    private val fxRateService = mockk<FxRateService>()
    private val service = CurrencyConversionService(fxRateService)

    private fun price(ticker: String, date: LocalDate, close: BigDecimal, currency: String) =
        CachedPrice(ticker = ticker, priceDate = date, closePrice = close, currency = currency)

    @Test
    fun `returns EUR prices unchanged`() {
        val prices = listOf(
            price("TEP.PA", LocalDate.of(2025, 6, 15), BigDecimal("50.00"), "EUR"),
            price("TEP.PA", LocalDate.of(2025, 6, 16), BigDecimal("51.00"), "EUR"),
        )

        val result = service.convertToEur(prices, "EUR")

        assertEquals(2, result.size)
        assertEquals(BigDecimal("50.00"), result[0].second)
        assertEquals(BigDecimal("51.00"), result[1].second)
    }

    @Test
    fun `converts USD prices to EUR by dividing by rate`() {
        val date1 = LocalDate.of(2025, 6, 15)
        val date2 = LocalDate.of(2025, 6, 16)
        val prices = listOf(
            price("GOOG", date1, BigDecimal("119.00"), "USD"),
            price("GOOG", date2, BigDecimal("238.00"), "USD"),
        )

        every { fxRateService.getRatesForDateRange("USD", date1, date2) } returns listOf(
            CachedFxRate(currencyPair = "EURUSD", rateDate = date1, rate = BigDecimal("1.19")),
            CachedFxRate(currencyPair = "EURUSD", rateDate = date2, rate = BigDecimal("1.19")),
        )

        val result = service.convertToEur(prices, "USD")

        assertEquals(2, result.size)
        // 119.00 / 1.19 = 100.00
        assertEquals(BigDecimal("100.000000"), result[0].second)
        // 238.00 / 1.19 = 200.00
        assertEquals(BigDecimal("200.000000"), result[1].second)
    }

    @Test
    fun `forward-fills FX rate for missing dates like weekends`() {
        val friday = LocalDate.of(2025, 6, 13)
        val saturday = LocalDate.of(2025, 6, 14)

        val prices = listOf(
            price("GOOG", friday, BigDecimal("119.00"), "USD"),
            price("GOOG", saturday, BigDecimal("119.00"), "USD"),
        )

        // Only Friday has an FX rate (no weekend rate)
        every { fxRateService.getRatesForDateRange("USD", friday, saturday) } returns listOf(
            CachedFxRate(currencyPair = "EURUSD", rateDate = friday, rate = BigDecimal("1.19")),
        )

        val result = service.convertToEur(prices, "USD")

        assertEquals(2, result.size)
        // Saturday uses Friday's rate via forward-fill
        assertEquals(result[0].second, result[1].second)
    }

    @Test
    fun `returns empty list for empty prices`() {
        val result = service.convertToEur(emptyList(), "USD")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `throws when no FX rate available`() {
        val date = LocalDate.of(2025, 6, 15)
        val prices = listOf(price("GOOG", date, BigDecimal("119.00"), "USD"))

        every { fxRateService.getRatesForDateRange("USD", date, date) } returns emptyList()

        val ex = assertThrows<MarketDataException> {
            service.convertToEur(prices, "USD")
        }
        // The message must name the CURRENCY PAIR (EUR/USD), not just "USD".
        // FX rates are bidirectional and stored as EUR/X — knowing only one
        // side leaves the reader guessing which lookup failed.
        assertTrue(
            ex.message!!.contains("EUR/USD"),
            "expected message to name the EUR/USD pair, got: ${ex.message}",
        )
        assertTrue(ex.message!!.contains(date.toString()))
    }

    @Test
    fun `cross-currency conversion error names the target pair`() {
        // SEK -> USD goes SEK -> EUR -> USD. Failure on the second leg
        // should blame EUR/USD, not bare "USD".
        val date = LocalDate.of(2025, 6, 15)
        val prices = listOf(price("VOLVO-B", date, BigDecimal("300"), "SEK"))

        every { fxRateService.getRatesForDateRange("SEK", date, date) } returns listOf(
            CachedFxRate(currencyPair = "EURSEK", rateDate = date, rate = BigDecimal("11.50")),
        )
        every { fxRateService.getRatesForDateRange("USD", date, date) } returns emptyList()

        val ex = assertThrows<MarketDataException> {
            service.convertTo(prices, "SEK", "USD")
        }
        assertTrue(
            ex.message!!.contains("EUR/USD"),
            "expected message to name the EUR/USD pair, got: ${ex.message}",
        )
    }
}
