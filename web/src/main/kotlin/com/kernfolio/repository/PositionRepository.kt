package com.kernfolio.repository

import com.kernfolio.domain.Position
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface PositionRepository : ListCrudRepository<Position, UUID> {
    fun findByPortfolioId(portfolioId: UUID): List<Position>
    fun deleteByPortfolioId(portfolioId: UUID)
}
