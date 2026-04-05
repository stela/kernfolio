package com.kernfolio.controller.api

import com.kernfolio.dto.PortfolioEntryData
import com.kernfolio.dto.PositionEntryDto
import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.PortfolioService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

@RestController
@RequestMapping("/api/portfolios")
class PortfolioApiController(
    private val portfolioService: PortfolioService,
) {

    @GetMapping("/{id}/entry-data")
    fun entryData(
        @PathVariable id: UUID,
        authentication: Authentication,
    ): ResponseEntity<PortfolioEntryData> {
        val userId = (authentication.principal as KernfolioUserDetails).id
        val portfolio = portfolioService.findByIdAndUserId(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val positions = portfolioService.findPositionsByPortfolioId(id, userId)

        return ResponseEntity.ok(
            PortfolioEntryData(
                baseCurrency = portfolio.baseCurrency,
                positions = positions.map { pos ->
                    PositionEntryDto(
                        id = pos.id.toString(),
                        positionType = pos.positionType,
                        ticker = pos.ticker,
                        name = pos.name,
                        currency = pos.currency,
                        weightPct = pos.weightPct,
                        costBasisPct = pos.costBasisPct,
                        intrinsicValueLocal = pos.intrinsicValueLocal,
                        confidencePct = pos.confidencePct,
                        sector = pos.sector,
                    )
                },
            )
        )
    }
}
