package com.kernfolio.twin.frankfurter

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import tools.jackson.core.type.TypeReference
import java.math.BigDecimal
import java.time.LocalDate

@Service
class FxRateFixtureStore(jsonMapper: JsonMapper) {

    private val allRates: Map<LocalDate, Map<String, BigDecimal>>

    init {
        val resource = ClassPathResource("fixtures/fx-rates.json")
        val typeRef = object : TypeReference<Map<String, Map<String, Map<String, BigDecimal>>>>() {}
        val raw: Map<String, Map<String, Map<String, BigDecimal>>> =
            jsonMapper.readValue(resource.inputStream, typeRef)
        allRates = raw["rates"]!!
            .mapKeys { (dateStr, _) -> LocalDate.parse(dateStr) }
            .toSortedMap()
    }

    fun getRates(
        startDate: LocalDate,
        endDate: LocalDate,
        targetCurrencies: Set<String>
    ): Map<LocalDate, Map<String, BigDecimal>> {
        return allRates.entries
            .filter { (date, _) -> !date.isBefore(startDate) && !date.isAfter(endDate) }
            .associate { (date, currencies) ->
                date to if (targetCurrencies.isEmpty()) {
                    currencies
                } else {
                    currencies.filterKeys { it in targetCurrencies }
                }
            }
    }

    val availableCurrencies: Set<String>
        get() = allRates.values.firstOrNull()?.keys ?: emptySet()
}
