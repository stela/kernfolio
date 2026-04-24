package com.kernfolio.controller.api

import com.kernfolio.dto.PortfolioEntryData
import com.kernfolio.dto.PositionEntryDto
import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.PortfolioNotFoundException
import com.kernfolio.service.PortfolioService
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

@RestController
@RequestMapping("/api/portfolios")
class PortfolioApiController(
    private val portfolioService: PortfolioService,
) {

    private val log = LoggerFactory.getLogger(PortfolioApiController::class.java)

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
                        currency = pos.currency,
                        weightPct = pos.weightPct,
                        costBasisPct = pos.costBasisPct,
                        intrinsicValueLocal = pos.intrinsicValueLocal,
                        confidence = pos.confidence,
                        sector = pos.sector,
                    )
                },
            )
        )
    }

    // Bulk partial update of weight_pct only. Triggered by the client when
    // the (local) total portfolio value changes — we re-derive each
    // position's live weight and push the set to the server so the stored
    // values stay in sync with what the UI is rendering.
    @PostMapping("/{id}/rebalance")
    @Transactional
    fun rebalancePositions(
        @PathVariable id: UUID,
        @RequestBody request: RebalanceRequest,
        authentication: Authentication,
    ): ResponseEntity<Void> {
        val userId = (authentication.principal as KernfolioUserDetails).id
        portfolioService.findByIdAndUserId(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)

        request.weights.forEach { (positionIdStr, weightPct) ->
            val positionId = try {
                UUID.fromString(positionIdStr)
            } catch (_: IllegalArgumentException) {
                return@forEach
            }
            try {
                portfolioService.updatePositionWeight(positionId, id, userId, weightPct)
            } catch (_: PortfolioNotFoundException) {
                // Position may have been deleted mid-rebalance; carry on.
                log.debug("Skipped rebalance for missing position {}", positionId)
            }
        }
        return ResponseEntity.noContent().build()
    }
}

data class RebalanceRequest(val weights: Map<String, BigDecimal>)
