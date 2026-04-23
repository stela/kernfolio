package com.kernfolio.controller.api

import com.kernfolio.service.MarketDataService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

@RestController
@RequestMapping("/api/prices")
class PriceController(
    private val marketDataService: MarketDataService,
) {

    private val log = LoggerFactory.getLogger(PriceController::class.java)

    @GetMapping("/latest")
    fun getLatestPrices(
        @RequestParam tickers: List<String>,
    ): ResponseEntity<Map<String, LatestPriceDto?>> {
        val prices = marketDataService.getLatestPrices(tickers)
        val dtos = prices.mapValues { (_, price) ->
            price?.let {
                LatestPriceDto(
                    ticker = it.ticker,
                    date = it.priceDate,
                    close = it.closePrice,
                    currency = it.currency,
                )
            }
        }
        return ResponseEntity.ok(dtos)
    }

    // Triggers an on-demand fetch from the optimizer (yfinance) for a single
    // ticker, caches the result, and returns the freshest row. Used by the
    // new-position autocomplete so the user sees a real price + currency as
    // soon as they pick a suggestion — without this, the UI shows "no data
    // yet" until the nightly scheduled refresh runs.
    @PostMapping("/refresh")
    fun refreshPrice(
        @RequestBody request: RefreshPriceRequest,
    ): ResponseEntity<Map<String, LatestPriceDto?>> {
        val ticker = request.ticker.trim()
        if (ticker.isEmpty() || ticker.length > MAX_TICKER_LENGTH) {
            return ResponseEntity.badRequest().build()
        }

        try {
            marketDataService.fetchAndCachePrices(listOf(ticker))
        } catch (e: Exception) {
            log.warn("Failed to refresh price for {}: {}", ticker, e.message)
        }

        val cached = marketDataService.getLatestPrices(listOf(ticker))[ticker]
        val dto = cached?.let {
            LatestPriceDto(
                ticker = it.ticker,
                date = it.priceDate,
                close = it.closePrice,
                currency = it.currency,
            )
        }
        return ResponseEntity.ok(mapOf(ticker to dto))
    }

    companion object {
        private const val MAX_TICKER_LENGTH = 30
    }
}

data class LatestPriceDto(
    val ticker: String,
    val date: LocalDate,
    val close: BigDecimal,
    val currency: String,
)

data class RefreshPriceRequest(val ticker: String)
