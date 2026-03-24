package com.kernfolio.config.converter

import com.kernfolio.domain.OptimizationParameters
import com.kernfolio.domain.OptimizationResults
import org.postgresql.util.PGobject
import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import tools.jackson.databind.ObjectMapper

@WritingConverter
class OptimizationParametersWritingConverter(
    private val objectMapper: ObjectMapper,
) : Converter<OptimizationParameters, PGobject> {
    override fun convert(source: OptimizationParameters): PGobject {
        return PGobject().apply {
            type = "jsonb"
            value = objectMapper.writeValueAsString(source)
        }
    }
}

@ReadingConverter
class OptimizationParametersReadingConverter(
    private val objectMapper: ObjectMapper,
) : Converter<PGobject, OptimizationParameters> {
    override fun convert(source: PGobject): OptimizationParameters {
        return objectMapper.readValue(source.value!!, OptimizationParameters::class.java)
    }
}

@WritingConverter
class OptimizationResultsWritingConverter(
    private val objectMapper: ObjectMapper,
) : Converter<OptimizationResults, PGobject> {
    override fun convert(source: OptimizationResults): PGobject {
        return PGobject().apply {
            type = "jsonb"
            value = objectMapper.writeValueAsString(source)
        }
    }
}

@ReadingConverter
class OptimizationResultsReadingConverter(
    private val objectMapper: ObjectMapper,
) : Converter<PGobject, OptimizationResults> {
    override fun convert(source: PGobject): OptimizationResults {
        return objectMapper.readValue(source.value!!, OptimizationResults::class.java)
    }
}
