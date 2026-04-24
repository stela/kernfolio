package com.kernfolio.service

import com.kernfolio.config.MarketDataProperties
import com.kernfolio.domain.CachedFxRate
import com.kernfolio.marketdata.FrankfurterClient
import com.kernfolio.marketdata.MarketDataException
import com.kernfolio.repository.CachedFxRateRepository
import com.kernfolio.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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

        // Upper bound on a single Frankfurter request window. Keeps us from
        // slamming a free public API with a 5-year fetch when only the edges
        // of the window are missing; also bounds retry cost on transient
        // failure. Tuned for "big enough that a cold 5-year backfill is only
        // a handful of calls, small enough that any single failure is cheap
        // to redo."
        private const val FX_BACKFILL_CHUNK_DAYS = 365L
    }

    // Returns the sub-ranges of [windowStart, windowEnd] that are missing
    // from the cache for the given pair. Assumes the cache is built by a
    // contiguous-upsert writer, so a hole in the middle of
    // [earliestCached, latestCached] is not considered here — findRate's
    // weekend-forward-fill in CurrencyConversionService absorbs small holes.
    private fun missingRanges(
        pair: String,
        windowStart: LocalDate,
        windowEnd: LocalDate,
    ): List<Pair<LocalDate, LocalDate>> {
        val earliest = cachedFxRateRepository.findEarliestByCurrencyPair(pair)?.rateDate
        val latest = cachedFxRateRepository.findLatestByCurrencyPair(pair)?.rateDate
        if (earliest == null || latest == null) return listOf(windowStart to windowEnd)
        val gaps = mutableListOf<Pair<LocalDate, LocalDate>>()
        if (earliest.isAfter(windowStart)) gaps += windowStart to earliest.minusDays(1)
        if (latest.isBefore(windowEnd)) gaps += latest.plusDays(1) to windowEnd
        return gaps
    }

    // On-demand historical backfill used by the optimizer before running
    // currency conversion. Fetches only the missing sub-ranges per currency
    // and chunks requests to FX_BACKFILL_CHUNK_DAYS so we don't hand
    // Frankfurter a 5-year-wide request.
    fun ensureCoverage(
        currencies: Collection<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ) {
        if (startDate.isAfter(endDate)) return

        // currency -> list of (gapStart, gapEnd); currencies with identical
        // gap lists can share a single multi-currency Frankfurter call.
        val gapsByCurrency = currencies
            .asSequence()
            .map { it.uppercase() }
            .filter { it != BASE_CURRENCY }
            .distinct()
            .associateWith { missingRanges("$BASE_CURRENCY$it", startDate, endDate) }
            .filterValues { it.isNotEmpty() }

        if (gapsByCurrency.isEmpty()) return

        // Group currencies by their gap list so identical-gap currencies
        // share a request. Typical case: everything has the same old-side
        // gap because the cache was seeded by the same 14-day UI fetch.
        val currenciesByGapSet = gapsByCurrency.entries
            .groupBy({ it.value }, { it.key })

        for ((gapList, currenciesForGroup) in currenciesByGapSet) {
            for ((gapStart, gapEnd) in gapList) {
                var chunkStart = gapStart
                while (!chunkStart.isAfter(gapEnd)) {
                    val chunkEnd = minOf(
                        chunkStart.plusDays(FX_BACKFILL_CHUNK_DAYS - 1),
                        gapEnd,
                    )
                    log.info(
                        "FX backfill: {} for {} currencies ({}..{})",
                        "$BASE_CURRENCY/${currenciesForGroup.joinToString(",")}",
                        currenciesForGroup.size, chunkStart, chunkEnd,
                    )
                    val response = frankfurterClient.fetchFxRates(
                        BASE_CURRENCY, currenciesForGroup, chunkStart, chunkEnd
                    )
                    response.rates.forEach { (date, currencyRates) ->
                        currencyRates.forEach { (currency, rate) ->
                            cachedFxRateRepository.upsert(
                                "$BASE_CURRENCY$currency", date, rate
                            )
                        }
                    }
                    chunkStart = chunkEnd.plusDays(1)
                }
            }
        }
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

    fun getLatestCrossRateInfoOrFetch(rawBase: String, rawTarget: String): CrossRateInfo? {
        // Currency codes are ISO 4217 — always upper-case. Normalising at the
        // entry point lets callers (HTTP controller, test code, future
        // callers) be lax without breaking the same-currency short-circuit or
        // producing bogus Frankfurter requests like "EUReur".
        val base = rawBase.trim().uppercase()
        val target = rawTarget.trim().uppercase()
        buildCrossRateInfo(base, target)?.let { return it }
        val missing = listOf(base, target).filter { it != BASE_CURRENCY }.distinct()
        if (missing.isEmpty()) return buildCrossRateInfo(base, target)

        // Anchor the fetch window in UTC. A "today" derived from the JVM default
        // zone would give different users different results. We don't actually
        // care which date today is — we upsert whatever Frankfurter returns and
        // then re-read "latest by pair", which gives the most-recent cached row.
        val endDate = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC)
        val startDate = endDate.minusDays(14)

        try {
            val response = frankfurterClient.fetchFxRates(BASE_CURRENCY, missing, startDate, endDate)
            response.rates.forEach { (date, currencyRates) ->
                currencyRates.forEach { (currency, rate) ->
                    cachedFxRateRepository.upsert("$BASE_CURRENCY$currency", date, rate)
                }
            }
        } catch (e: MarketDataException) {
            log.warn("On-demand FX fetch failed for {}/{}: {}", base, target, e.message)
            return null
        }
        return buildCrossRateInfo(base, target)
    }

    private fun buildCrossRateInfo(base: String, target: String): CrossRateInfo? {
        if (base == target) {
            val now = Instant.now()
            return CrossRateInfo(BigDecimal.ONE, LocalDate.ofInstant(now, ZoneOffset.UTC), now)
        }
        val baseCached = if (base == BASE_CURRENCY) null
            else cachedFxRateRepository.findLatestByCurrencyPair("$BASE_CURRENCY$base") ?: return null
        val targetCached = if (target == BASE_CURRENCY) null
            else cachedFxRateRepository.findLatestByCurrencyPair("$BASE_CURRENCY$target") ?: return null

        val eurBase = baseCached?.rate ?: BigDecimal.ONE
        val eurTarget = targetCached?.rate ?: BigDecimal.ONE
        val rate = eurTarget.divide(eurBase, 8, java.math.RoundingMode.HALF_UP)

        val caches = listOfNotNull(baseCached, targetCached)
        val asOf = caches.minOf { it.rateDate }
        // fetchedAt may be null on older rows that predate the column default;
        // treat those as epoch so the UI always has a value to render.
        val fetchedAt = caches.map { it.fetchedAt ?: Instant.EPOCH }.min()
        return CrossRateInfo(rate, asOf, fetchedAt)
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
