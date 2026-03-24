package com.kernfolio.repository

import com.kernfolio.domain.FeatureFlag
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface FeatureFlagRepository : ListCrudRepository<FeatureFlag, UUID> {
    fun findByFlagName(flagName: String): FeatureFlag?
}
