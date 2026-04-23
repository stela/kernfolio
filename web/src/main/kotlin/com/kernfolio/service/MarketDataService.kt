package com.kernfolio.service

import com.kernfolio.domain.CachedPrice
import com.kernfolio.domain.Instrument
import com.kernfolio.marketdata.TickerMapper
import com.kernfolio.marketdata.YFinanceFetcher
import com.kernfolio.marketdata.dto.TickerMetadata
import com.kernfolio.repository.CachedPriceRepository
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate

@Service
class MarketDataService(
    private val yFinanceFetcher: YFinanceFetcher,
    private val tickerMapper: TickerMapper,
    private val cachedPriceRepository: CachedPriceRepository,
    private val instrumentRepository: InstrumentRepository,
    private val positionRepository: PositionRepository,
    private val fxRateService: FxRateService,
) {
    private val log = LoggerFactory.getLogger(MarketDataService::class.java)

    @Scheduled(cron = "\${market-data.scheduler.cron}")
    fun scheduledRefresh() {
        log.info("Starting scheduled market data refresh")
        refreshAllPrices()
        fxRateService.refreshAllFxRates()
        log.info("Scheduled market data refresh complete")
    }

    fun refreshAllPrices() {
        val equityTickers = positionRepository.findAll()
            .filter { it.positionType == "EQUITY" }
            .map { it.ticker }
            .distinct()

        if (equityTickers.isEmpty()) {
            log.info("No equity positions found, skipping price refresh")
            return
        }

        fetchAndCachePrices(equityTickers)
    }

    fun fetchAndCachePrices(internalTickers: List<String>) {
        val today = LocalDate.now()

        val startDates = internalTickers.associateWith { ticker ->
            val maxDate = cachedPriceRepository.findMaxDateByTicker(ticker)
            maxDate?.plusDays(1) ?: today.minusYears(5)
        }

        val globalStart = startDates.values.min()
        if (!globalStart.isBefore(today)) {
            log.info("All prices up to date, nothing to fetch")
            return
        }

        val yfinanceTickers = tickerMapper.toYfinanceAll(internalTickers)

        log.info("Fetching prices for {} tickers from {}", yfinanceTickers.size, globalStart)
        val response = yFinanceFetcher.fetchPrices(yfinanceTickers, globalStart, today)

        response.errors.forEach { (ticker, error) ->
            log.warn("Error fetching {}: {}", ticker, error)
        }

        response.prices.forEach { (yTicker, points) ->
            val internal = tickerMapper.toInternal(yTicker)
            val currency = response.metadata[yTicker]?.currency ?: "USD"
            val tickerStart = startDates[internal] ?: globalStart

            points
                .filter { !it.date.isBefore(tickerStart) }
                .forEach { point ->
                    cachedPriceRepository.upsert(internal, point.date, point.close, currency)
                }
        }

        updateInstruments(response.metadata)

        log.info(
            "Cached prices for {} tickers ({} errors)",
            response.prices.size, response.errors.size,
        )
    }

    fun getLatestPrices(tickers: List<String>): Map<String, CachedPrice?> =
        tickers.associateWith { cachedPriceRepository.findLatestByTicker(it) }

    fun searchInstruments(query: String, limit: Int = 10): List<TickerSuggestion> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        val localHits = instrumentRepository.search(trimmed, limit).map {
            TickerSuggestion(
                ticker = it.ticker,
                name = it.name,
                exchange = it.exchange,
                currency = it.currency,
                sector = it.sector,
                source = "local",
            )
        }

        if (localHits.size >= limit) return localHits

        val remaining = limit - localHits.size
        val seen = localHits.map { it.ticker }.toMutableSet()

        val remoteHits = yFinanceFetcher.searchTickers(trimmed, remaining + localHits.size)
            .asSequence()
            .mapNotNull { remote ->
                val internal = tickerMapper.toInternal(remote.symbol)
                if (!seen.add(internal)) return@mapNotNull null
                TickerSuggestion(
                    ticker = internal,
                    name = remote.longname ?: remote.shortname,
                    exchange = remote.exchange,
                    currency = remote.currency,
                    sector = null,
                    source = "remote",
                )
            }
            .take(remaining)
            .toList()

        cacheRemoteSuggestions(remoteHits)

        return localHits + remoteHits
    }

    // Piggyback a metadata cache on every remote search hit: the
    // search endpoint already hands us {ticker, name, exchange, currency},
    // so we might as well persist it. Subsequent searches can serve that
    // row locally (no yfinance round-trip), and later price fetches
    // inherit the name/exchange instead of leaving them null.
    //
    // "Fill gaps" merge: never overwrite a richer existing field with a
    // search-level one (price-fetch metadata is usually more complete).
    private fun cacheRemoteSuggestions(suggestions: List<TickerSuggestion>) {
        suggestions.forEach { sug ->
            try {
                val existing = instrumentRepository.findById(sug.ticker).orElse(null)
                if (existing == null) {
                    instrumentRepository.save(
                        Instrument(
                            ticker = sug.ticker,
                            name = sug.name,
                            exchange = sug.exchange,
                            currency = sug.currency,
                        )
                    )
                    return@forEach
                }

                val mergedName = existing.name ?: sug.name
                val mergedExchange = existing.exchange ?: sug.exchange
                val mergedCurrency = existing.currency ?: sug.currency
                val changed = mergedName != existing.name ||
                    mergedExchange != existing.exchange ||
                    mergedCurrency != existing.currency
                if (!changed) return@forEach

                instrumentRepository.save(
                    Instrument(
                        ticker = existing.ticker,
                        name = mergedName,
                        exchange = mergedExchange,
                        currency = mergedCurrency,
                        sector = existing.sector,
                        marketCapUsd = existing.marketCapUsd,
                        fractional = existing.fractional,
                        lastRefreshedAt = existing.lastRefreshedAt,
                    ).markNotNew()
                )
            } catch (e: Exception) {
                log.warn("Failed to cache search suggestion {}: {}", sug.ticker, e.message)
            }
        }
    }

    private fun updateInstruments(metadata: Map<String, TickerMetadata>) {
        metadata.forEach { (yTicker, meta) ->
            val internal = tickerMapper.toInternal(yTicker)
            val existing = instrumentRepository.findById(internal).orElse(null)
            val instrument = Instrument(
                ticker = internal,
                name = meta.name,
                exchange = null,
                currency = meta.currency,
                sector = meta.sector,
                marketCapUsd = meta.marketCap,
                lastRefreshedAt = Instant.now(),
            ).let { if (existing != null) it.markNotNew() else it }
            instrumentRepository.save(instrument)
        }
    }
}
