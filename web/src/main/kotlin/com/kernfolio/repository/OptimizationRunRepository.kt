package com.kernfolio.repository

import com.kernfolio.domain.OptimizationRun
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface OptimizationRunRepository : ListCrudRepository<OptimizationRun, UUID> {
    fun findByPortfolioId(portfolioId: UUID): List<OptimizationRun>

    @Query("SELECT * FROM optimization_runs WHERE portfolio_id = :portfolioId ORDER BY created_at DESC LIMIT :limit")
    fun findRecentByPortfolioId(portfolioId: UUID, limit: Int): List<OptimizationRun>
}
