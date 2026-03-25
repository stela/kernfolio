package com.kernfolio.service

import com.kernfolio.domain.CachedPrice
import com.kernfolio.marketdata.MarketDataException
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Service
class CurrencyConversionService(
    private val fxRateService: FxRateService,
) {

    fun convertToEur(
        prices: List<CachedPrice>,
        sourceCurrency: String,
    ): List<Pair<LocalDate, BigDecimal>> {
        if (sourceCurrency == FxRateService.BASE_CURRENCY) {
            return prices.map { it.priceDate to it.closePrice }
        }

        if (prices.isEmpty()) return emptyList()

        val startDate = prices.first().priceDate
        val endDate = prices.last().priceDate
        val fxRates = fxRateService.getRatesForDateRange(sourceCurrency, startDate, endDate)

        val rateByDate = fxRates.associate { it.rateDate to it.rate }

        return prices.map { price ->
            val rate = findRate(rateByDate, price.priceDate)
                ?: throw MarketDataException(
                    "No FX rate available for $sourceCurrency on or before ${price.priceDate}"
                )
            // EUR/USD = 1.19 means 1 EUR = 1.19 USD, so price_in_EUR = price_in_USD / rate
            price.priceDate to price.closePrice.divide(rate, 6, RoundingMode.HALF_UP)
        }
    }

    private fun findRate(
        rateByDate: Map<LocalDate, BigDecimal>,
        targetDate: LocalDate,
    ): BigDecimal? {
        rateByDate[targetDate]?.let { return it }
        // Forward-fill: find the most recent rate on or before the target date
        return rateByDate.keys
            .filter { it.isBefore(targetDate) }
            .maxOrNull()
            ?.let { rateByDate[it] }
    }
}
