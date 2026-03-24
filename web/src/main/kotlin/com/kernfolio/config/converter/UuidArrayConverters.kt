package com.kernfolio.config.converter

import org.springframework.core.convert.converter.Converter
import org.springframework.data.convert.ReadingConverter
import org.springframework.data.convert.WritingConverter
import org.springframework.data.jdbc.core.mapping.JdbcValue
import java.sql.JDBCType
import java.util.UUID

@ReadingConverter
class SqlArrayToUuidListReadingConverter : Converter<java.sql.Array, List<UUID>> {
    override fun convert(source: java.sql.Array): List<UUID> {
        @Suppress("UNCHECKED_CAST")
        val array = source.array as Array<UUID>
        return array.toList()
    }
}

@WritingConverter
class UuidListToJdbcValueWritingConverter : Converter<List<UUID>, JdbcValue> {
    override fun convert(source: List<UUID>): JdbcValue {
        val arrayString = source.joinToString(",", "{", "}") { it.toString() }
        return JdbcValue.of(arrayString, JDBCType.OTHER)
    }
}
