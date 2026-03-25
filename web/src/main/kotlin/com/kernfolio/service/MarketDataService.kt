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
