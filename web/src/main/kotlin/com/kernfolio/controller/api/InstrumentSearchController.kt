package com.kernfolio.controller.api

import com.kernfolio.service.MarketDataService
import com.kernfolio.service.TickerSuggestion
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/instruments")
class InstrumentSearchController(
    private val marketDataService: MarketDataService,
) {

    @GetMapping("/search")
    fun search(
        @RequestParam("q") q: String,
        @RequestParam(name = "limit", defaultValue = "10") limit: Int,
    ): ResponseEntity<List<TickerSuggestionDto>> {
        val trimmed = q.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_QUERY_LENGTH) {
            return ResponseEntity.badRequest().build()
        }
        val clampedLimit = limit.coerceIn(1, MAX_LIMIT)

        val suggestions = marketDataService.searchInstruments(trimmed, clampedLimit)
        return ResponseEntity.ok(suggestions.map { it.toDto() })
    }

    private fun TickerSuggestion.toDto() = TickerSuggestionDto(
        ticker = ticker,
        name = name,
        exchange = exchange,
        currency = currency,
        sector = sector,
        source = source,
    )

    companion object {
        private const val MAX_QUERY_LENGTH = 50
        private const val MAX_LIMIT = 20
    }
}

data class TickerSuggestionDto(
    val ticker: String,
    val name: String?,
    val exchange: String?,
    val currency: String?,
    val sector: String?,
    val source: String,
)
