package com.kernfolio.twin.frankfurter

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException

@RestController
class FxRateController(private val fixtureStore: FxRateFixtureStore) {

    @GetMapping("/v1/{dateRange}")
    fun getTimeSeries(
        @PathVariable dateRange: String,
        @RequestParam(defaultValue = "EUR") from: String,
        @RequestParam(defaultValue = "") to: String
    ): ResponseEntity<Any> {
        if (from != "EUR") {
            return ResponseEntity.badRequest()
                .body(mapOf("message" to "Only EUR base currency is supported"))
        }

        val dates = dateRange.split("..")
        if (dates.size != 2) {
            return ResponseEntity.badRequest()
                .body(mapOf("message" to "Date range must be in format {start_date}..{end_date}"))
        }

        val startDate: LocalDate
        val endDate: LocalDate
        try {
            startDate = LocalDate.parse(dates[0])
            endDate = LocalDate.parse(dates[1])
        } catch (e: DateTimeParseException) {
            return ResponseEntity.badRequest()
                .body(mapOf("message" to "Invalid date format: ${e.message}"))
        }

        if (endDate.isBefore(startDate)) {
            return ResponseEntity.badRequest()
                .body(mapOf("message" to "end_date must not be before start_date"))
        }

        val targetCurrencies = if (to.isBlank()) emptySet() else to.split(",").map { it.trim() }.toSet()

        val rates = fixtureStore.getRates(startDate, endDate, targetCurrencies)

        val responseRates = rates.mapKeys { (date, _) -> date.toString() }

        return ResponseEntity.ok(
            FxRateResponse(
                amount = BigDecimal.ONE,
                base = "EUR",
                startDate = startDate.toString(),
                endDate = endDate.toString(),
                rates = responseRates
            )
        )
    }

    @GetMapping("/health")
    fun health(): Map<String, String> = mapOf("status" to "ok")
}

data class FxRateResponse(
    val amount: BigDecimal,
    val base: String,
    @get:JsonProperty("start_date") val startDate: String,
    @get:JsonProperty("end_date") val endDate: String,
    val rates: Map<String, Map<String, BigDecimal>>
)
