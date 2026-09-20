package com.kernfolio.service

import com.kernfolio.domain.Portfolio
import com.kernfolio.domain.Position
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.PortfolioRepository
import com.kernfolio.repository.PositionRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

class PortfolioNotFoundException(message: String) : RuntimeException(message)

class InvalidPositionException(message: String) : RuntimeException(message)

data class PositionForm(
    val positionType: String = "EQUITY",
    val ticker: String = "",
    val currency: String = "USD",
    val weightPct: BigDecimal = BigDecimal.ZERO,
    val costBasisPct: BigDecimal? = null,
    val expectedReturn: BigDecimal? = null,
    val returnStddev: BigDecimal? = null,
    val sector: String? = null,
    val notes: String? = null,
) {
    // A view is a return *and* how sure you are of it: Black-Litterman can't
    // weigh one without the other, and a silent default would be a made-up
    // opinion. No view at all is fine — the market's estimate is used.
    fun validate() {
        if ((expectedReturn == null) != (returnStddev == null)) {
            throw InvalidPositionException("Expected return and its standard deviation must be given together")
        }
        if (expectedReturn != null && expectedReturn <= BigDecimal.ONE.negate()) {
            throw InvalidPositionException("Expected return must be above -100%")
        }
        if (returnStddev != null && returnStddev <= BigDecimal.ZERO) {
            throw InvalidPositionException("Standard deviation must be positive")
        }
        if (positionType != "EQUITY" && expectedReturn != null) {
            throw InvalidPositionException("Only equity positions can carry a view")
        }
    }
}

@Service
class PortfolioService(
    private val portfolioRepository: PortfolioRepository,
    private val positionRepository: PositionRepository,
    private val instrumentRepository: InstrumentRepository,
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
        form.validate()
        findByIdAndUserId(portfolioId, userId)
            ?: throw PortfolioNotFoundException("Portfolio not found")
        return positionRepository.save(
            Position(
                portfolioId = portfolioId,
                positionType = form.positionType,
                ticker = form.ticker,
                currency = form.currency,
                weightPct = form.weightPct,
                costBasisPct = form.costBasisPct,
                expectedReturn = form.expectedReturn,
                returnStddev = form.returnStddev,
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
        form.validate()
        val existing = findPosition(positionId, portfolioId, userId)
        return positionRepository.save(
            existing.copy(
                positionType = form.positionType,
                ticker = form.ticker,
                currency = form.currency,
                weightPct = form.weightPct,
                costBasisPct = form.costBasisPct,
                expectedReturn = form.expectedReturn,
                returnStddev = form.returnStddev,
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

    // Display-name resolution. `positions` no longer carries a `name`
    // column — authoritative names live in `instruments` for equities and
    // are synthesized from currency for cash. Renders go through these
    // helpers; they are the only place that knows how to derive a name.
    fun displayNameFor(position: Position): String = when (position.positionType) {
        "CASH" -> "${position.currency} Cash"
        else -> instrumentRepository.findById(position.ticker).orElse(null)?.name
            ?: position.ticker
    }

    fun displayNames(positions: List<Position>): Map<UUID, String> {
        val equityTickers = positions
            .filter { it.positionType != "CASH" }
            .map { it.ticker }
            .distinct()
        val byTicker = if (equityTickers.isEmpty()) emptyMap()
            else instrumentRepository.findByTickers(equityTickers)
                .associate { it.ticker to (it.name ?: it.ticker) }
        return positions.mapNotNull { pos ->
            val id = pos.id ?: return@mapNotNull null
            id to when (pos.positionType) {
                "CASH" -> "${pos.currency} Cash"
                else -> byTicker[pos.ticker] ?: pos.ticker
            }
        }.toMap()
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
