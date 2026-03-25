package com.kernfolio.controller.api

import com.kernfolio.service.MarketDataService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
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
}

data class LatestPriceDto(
    val ticker: String,
    val date: LocalDate,
    val close: BigDecimal,
    val currency: String,
)
