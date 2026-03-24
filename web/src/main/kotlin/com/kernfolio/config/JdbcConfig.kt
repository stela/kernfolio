package com.kernfolio.config

import com.kernfolio.config.converter.OptimizationParametersReadingConverter
import com.kernfolio.config.converter.OptimizationParametersWritingConverter
import com.kernfolio.config.converter.OptimizationResultsReadingConverter
import com.kernfolio.config.converter.OptimizationResultsWritingConverter
import com.kernfolio.config.converter.SqlArrayToUuidListReadingConverter
import com.kernfolio.config.converter.UuidListToJdbcValueWritingConverter
import org.springframework.context.annotation.Configuration
import org.springframework.data.jdbc.repository.config.AbstractJdbcConfiguration
import tools.jackson.databind.ObjectMapper

@Configuration
class JdbcConfig(private val objectMapper: ObjectMapper) : AbstractJdbcConfiguration() {

    override fun userConverters(): MutableList<*> {
        return mutableListOf(
            OptimizationParametersWritingConverter(objectMapper),
            OptimizationParametersReadingConverter(objectMapper),
            OptimizationResultsWritingConverter(objectMapper),
            OptimizationResultsReadingConverter(objectMapper),
            SqlArrayToUuidListReadingConverter(),
            UuidListToJdbcValueWritingConverter(),
        )
    }
}
