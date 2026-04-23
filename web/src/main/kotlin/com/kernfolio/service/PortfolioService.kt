package com.kernfolio.service

import com.kernfolio.domain.Portfolio
import com.kernfolio.domain.Position
import com.kernfolio.repository.PortfolioRepository
import com.kernfolio.repository.PositionRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

class PortfolioNotFoundException(message: String) : RuntimeException(message)

data class PositionForm(
    val positionType: String = "EQUITY",
    val ticker: String = "",
    val name: String? = null,
    val currency: String = "USD",
    val weightPct: BigDecimal = BigDecimal.ZERO,
    val costBasisPct: BigDecimal? = null,
    val intrinsicValueLocal: BigDecimal? = null,
    val confidencePct: BigDecimal? = null,
    val sector: String? = null,
    val notes: String? = null,
)

@Service
class PortfolioService(
    private val portfolioRepository: PortfolioRepository,
    private val positionRepository: PositionRepository,
) {

    fun findByUserId(userId: UUID): List<Portfolio> =
        portfolioRepository.findByUserId(userId)

    fun findByIdAndUserId(id: UUID, userId: UUID): Portfolio? {
        val portfolio = portfolioRepository.findById(id).orElse(null) ?: return null
        return if (portfolio.userId == userId) portfolio else null
    }

    fun create(userId: UUID, name: String, description: String?, baseCurrency: String): Portfolio =
        portfolioRepository.save(
            Portfolio(
                userId = userId,
                name = name,
                description = description,
                baseCurrency = baseCurrency,
            )
        )

    fun update(id: UUID, userId: UUID, name: String, description: String?, baseCurrency: String): Portfolio {
        val portfolio = findByIdAndUserId(id, userId)
            ?: throw PortfolioNotFoundException("Portfolio not found")
        return portfolioRepository.save(
            portfolio.copy(name = name, description = description, baseCurrency = baseCurrency)
        )
    }

    fun delete(id: UUID, userId: UUID) {
        findByIdAndUserId(id, userId) ?: throw PortfolioNotFoundException("Portfolio not found")
        positionRepository.deleteByPortfolioId(id)
        portfolioRepository.deleteById(id)
    }

    fun findPositionsByPortfolioId(portfolioId: UUID, userId: UUID): List<Position> {
        findByIdAndUserId(portfolioId, userId)
            ?: throw PortfolioNotFoundException("Portfolio not found")
        return positionRepository.findByPortfolioId(portfolioId)
    }

    fun addPosition(portfolioId: UUID, userId: UUID, form: PositionForm): Position {
        findByIdAndUserId(portfolioId, userId)
            ?: throw PortfolioNotFoundException("Portfolio not found")
        return positionRepository.save(
            Position(
                portfolioId = portfolioId,
                positionType = form.positionType,
                ticker = form.ticker,
                name = form.name,
                currency = form.currency,
                weightPct = form.weightPct,
                costBasisPct = form.costBasisPct,
                intrinsicValueLocal = form.intrinsicValueLocal,
                confidencePct = form.confidencePct,
                sector = form.sector,
                notes = form.notes,
            )
        )
    }

    fun findPosition(positionId: UUID, portfolioId: UUID, userId: UUID): Position {
        findByIdAndUserId(portfolioId, userId)
            ?: throw PortfolioNotFoundException("Portfolio not found")
        val position = positionRepository.findById(positionId).orElse(null)
            ?: throw PortfolioNotFoundException("Position not found")
        if (position.portfolioId != portfolioId) {
            throw PortfolioNotFoundException("Position not found")
        }
        return position
    }

    fun updatePosition(
        positionId: UUID,
        portfolioId: UUID,
        userId: UUID,
        form: PositionForm,
    ): Position {
        val existing = findPosition(positionId, portfolioId, userId)
        return positionRepository.save(
            existing.copy(
                positionType = form.positionType,
                ticker = form.ticker,
                name = form.name,
                currency = form.currency,
                weightPct = form.weightPct,
                costBasisPct = form.costBasisPct,
                intrinsicValueLocal = form.intrinsicValueLocal,
                confidencePct = form.confidencePct,
                sector = form.sector,
                notes = form.notes,
            )
        )
    }

    // Partial update: only the weight_pct field. Used by the auto-rebalance
    // path when the user changes the local total portfolio value — we want
    // to sync the derived weights to the server without touching any of
    // the richer metadata (name, sector, notes) saved against the position.
    fun updatePositionWeight(
        positionId: UUID,
        portfolioId: UUID,
        userId: UUID,
        weightPct: BigDecimal,
    ): Position {
        val existing = findPosition(positionId, portfolioId, userId)
        if (existing.weightPct.compareTo(weightPct) == 0) return existing
        return positionRepository.save(existing.copy(weightPct = weightPct))
    }

    fun deletePosition(positionId: UUID, portfolioId: UUID, userId: UUID) {
        findByIdAndUserId(portfolioId, userId)
            ?: throw PortfolioNotFoundException("Portfolio not found")
        val position = positionRepository.findById(positionId).orElse(null)
            ?: throw PortfolioNotFoundException("Position not found")
        if (position.portfolioId != portfolioId) {
            throw PortfolioNotFoundException("Position not found")
        }
        positionRepository.deleteById(positionId)
    }
}
