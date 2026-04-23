package com.kernfolio.controller.api

import com.kernfolio.dto.LatestFxRateDto
import com.kernfolio.service.FxRateService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/fx")
class FxRateController(
    private val fxRateService: FxRateService,
) {

    // ISO 4217 codes are always three ASCII letters. Validate at the
    // HTTP boundary so callers get a clear 400 rather than an opaque
    // 500 or a silently empty response.
    private val currencyCodePattern = Regex("^[A-Za-z]{3}$")

    @GetMapping("/latest")
    fun getLatestRates(
        @RequestParam base: String,
        @RequestParam currencies: List<String>,
    ): ResponseEntity<Map<String, LatestFxRateDto?>> {
        requireCurrencyCode("base", base)
        currencies.forEach { requireCurrencyCode("currencies", it) }

        val rates = currencies.associateWith { target ->
            fxRateService.getLatestCrossRateInfoOrFetch(base, target)?.let {
                LatestFxRateDto(rate = it.rate, asOf = it.asOf, fetchedAt = it.fetchedAt)
            }
        }
        return ResponseEntity.ok(rates)
    }

    private fun requireCurrencyCode(name: String, value: String) {
        if (!currencyCodePattern.matches(value.trim())) {
            throw IllegalArgumentException(
                "$name must be an ISO 4217 three-letter code, got '$value'"
            )
        }
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleBadRequest(ex: IllegalArgumentException): ResponseEntity<Map<String, String>> =
        ResponseEntity.badRequest().body(mapOf("error" to (ex.message ?: "bad request")))
}
