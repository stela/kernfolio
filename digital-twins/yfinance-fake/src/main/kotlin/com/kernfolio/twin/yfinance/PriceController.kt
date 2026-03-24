package com.kernfolio.twin.yfinance

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class FetchPricesRequest(
    val tickers: List<String>,
    @param:JsonProperty("start_date") val startDate: String,
    @param:JsonProperty("end_date") val endDate: String
)

data class FetchPricesResponse(
    val prices: Map<String, List<PricePointDto>>,
    val metadata: Map<String, MetadataDto>,
    val errors: Map<String, String>
)

data class PricePointDto(
    val date: String,
    val close: BigDecimal
)

data class MetadataDto(
    val currency: String,
    val name: String,
    @get:JsonProperty("market_cap") val marketCap: Long,
    val sector: String
)

@RestController
class PriceController(private val fixtureStore: PriceFixtureStore) {

    @PostMapping("/fetch-prices")
    fun fetchPrices(@RequestBody request: FetchPricesRequest): ResponseEntity<Any> {
        if (request.tickers.isEmpty()) {
            return ResponseEntity.badRequest()
                .body(mapOf("message" to "tickers must not be empty"))
        }

        val startDate: LocalDate
        val endDate: LocalDate
        try {
            startDate = LocalDate.parse(request.startDate)
            endDate = LocalDate.parse(request.endDate)
        } catch (e: DateTimeParseException) {
            return ResponseEntity.badRequest()
                .body(mapOf("message" to "Invalid date format: ${e.message}"))
        }

        if (endDate.isBefore(startDate)) {
            return ResponseEntity.badRequest()
                .body(mapOf("message" to "end_date must not be before start_date"))
        }

        val requestedTickers = request.tickers.toSet()
        val unknownTickers = fixtureStore.findUnknownTickers(requestedTickers)
        val knownTickers = requestedTickers - unknownTickers

        val prices = fixtureStore.getPrices(knownTickers, startDate, endDate)
        val metadata = fixtureStore.getMetadata(knownTickers)

        val pricesDto = prices.mapValues { (_, points) ->
            points.map { PricePointDto(date = it.date.toString(), close = it.close) }
        }

        val metadataDto = metadata.mapValues { (_, meta) ->
            MetadataDto(
                currency = meta.currency,
                name = meta.name,
                marketCap = meta.marketCap,
                sector = meta.sector
            )
        }

        val errors = unknownTickers.associateWith { "No data found for ticker $it" }

        return ResponseEntity.ok(FetchPricesResponse(prices = pricesDto, metadata = metadataDto, errors = errors))
    }

    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "ok")
}
