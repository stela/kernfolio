package com.kernfolio.repository

import com.kernfolio.domain.Portfolio
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface PortfolioRepository : ListCrudRepository<Portfolio, UUID> {
    fun findByUserId(userId: UUID): List<Portfolio>
}
