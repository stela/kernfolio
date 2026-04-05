package com.kernfolio.controller.api

import com.kernfolio.dto.LatestFxRateDto
import com.kernfolio.service.FxRateService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/fx")
class FxRateController(
    private val fxRateService: FxRateService,
) {

    @GetMapping("/latest")
    fun getLatestRates(
        @RequestParam base: String,
        @RequestParam currencies: List<String>,
    ): ResponseEntity<Map<String, LatestFxRateDto?>> {
        val rates = currencies.associateWith { target ->
            fxRateService.getLatestCrossRate(base, target)?.let { LatestFxRateDto(rate = it) }
        }
        return ResponseEntity.ok(rates)
    }
}
