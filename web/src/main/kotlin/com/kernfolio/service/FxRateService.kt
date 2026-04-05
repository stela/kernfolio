package com.kernfolio.service

import com.kernfolio.config.MarketDataProperties
import com.kernfolio.domain.CachedFxRate
import com.kernfolio.marketdata.FrankfurterClient
import com.kernfolio.repository.CachedFxRateRepository
import com.kernfolio.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.LocalDate

@Service
class FxRateService(
    private val frankfurterClient: FrankfurterClient,
    private val cachedFxRateRepository: CachedFxRateRepository,
    private val positionRepository: PositionRepository,
    private val marketDataProperties: MarketDataProperties,
) {
    private val log = LoggerFactory.getLogger(FxRateService::class.java)

    companion object {
        const val BASE_CURRENCY = "EUR"
    }

    fun refreshAllFxRates() {
        val positionCurrencies = positionRepository.findAll()
            .map { it.currency }
            .filter { it != BASE_CURRENCY }
            .distinct()
        val allCurrencies = (marketDataProperties.defaultCurrencies + positionCurrencies).distinct()

        if (allCurrencies.isEmpty()) {
            log.info("No currencies to fetch FX rates for")
            return
        }

        val today = LocalDate.now()
        val startDate = allCurrencies.minOf { currency ->
            val pair = "$BASE_CURRENCY$currency"
            cachedFxRateRepository.findLatestByCurrencyPair(pair)
                ?.rateDate?.plusDays(1)
                ?: today.minusYears(5)
        }

        if (!startDate.isBefore(today)) {
            log.info("All FX rates up to date")
            return
        }

        log.info("Fetching FX rates for {} currencies from {}", allCurrencies.size, startDate)
        val response = frankfurterClient.fetchFxRates(BASE_CURRENCY, allCurrencies, startDate, today)

        response.rates.forEach { (date, currencyRates) ->
            currencyRates.forEach { (currency, rate) ->
                val pair = "$BASE_CURRENCY$currency"
                cachedFxRateRepository.upsert(pair, date, rate)
            }
        }

        log.info("Cached FX rates for {} dates", response.rates.size)
    }

    fun getLatestRate(currency: String): BigDecimal? {
        if (currency == BASE_CURRENCY) return BigDecimal.ONE
        val pair = "$BASE_CURRENCY$currency"
        return cachedFxRateRepository.findLatestByCurrencyPair(pair)?.rate
    }

    fun getLatestCrossRate(base: String, target: String): BigDecimal? {
        if (base == target) return BigDecimal.ONE
        val eurBase = getLatestRate(base) ?: return null
        val eurTarget = getLatestRate(target) ?: return null
        // EUR/base and EUR/target are stored. Cross rate = EUR/target ÷ EUR/base
        return eurTarget.divide(eurBase, 8, java.math.RoundingMode.HALF_UP)
    }

    fun getRatesForDateRange(
        currency: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): List<CachedFxRate> {
        if (currency == BASE_CURRENCY) return emptyList()
        val pair = "$BASE_CURRENCY$currency"
        return cachedFxRateRepository.findByCurrencyPairAndDateRange(pair, startDate, endDate)
    }
}
