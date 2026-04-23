package com.kernfolio.twin.yfinance

import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate

data class PricePoint(val date: LocalDate, val close: BigDecimal)

data class TickerMetadata(
    val currency: String,
    val name: String,
    val marketCap: Long,
    val sector: String
)

@Service
class PriceFixtureStore(jsonMapper: JsonMapper) {

    private val allPrices: Map<String, List<PricePoint>>
    private val allMetadata: Map<String, TickerMetadata>

    init {
        val resource = ClassPathResource("fixtures/fetch-prices-response.json")
        val typeRef = object : TypeReference<Map<String, Any>>() {}
        val raw: Map<String, Any> = jsonMapper.readValue(resource.inputStream, typeRef)

        @Suppress("UNCHECKED_CAST")
        val pricesRaw = raw["prices"] as Map<String, List<Map<String, Any>>>
        allPrices = pricesRaw.mapValues { (_, points) ->
            points.map { point ->
                PricePoint(
                    date = LocalDate.parse(point["date"] as String),
                    close = BigDecimal(point["close"].toString())
                )
            }.sortedBy { it.date }
        }

        @Suppress("UNCHECKED_CAST")
        val metadataRaw = raw["metadata"] as Map<String, Map<String, Any>>
        allMetadata = metadataRaw.mapValues { (_, meta) ->
            TickerMetadata(
                currency = meta["currency"] as String,
                name = meta["name"] as String,
                marketCap = (meta["market_cap"] as Number).toLong(),
                sector = meta["sector"] as String
            )
        }
    }

    fun getPrices(
        tickers: Set<String>,
        startDate: LocalDate,
        endDate: LocalDate
    ): Map<String, List<PricePoint>> {
        return tickers.mapNotNull { ticker ->
            allPrices[ticker]?.let { points ->
                val filtered = points.filter { !it.date.isBefore(startDate) && !it.date.isAfter(endDate) }
                ticker to filtered
            }
        }.toMap()
    }

    fun getMetadata(tickers: Set<String>): Map<String, TickerMetadata> {
        return tickers.mapNotNull { ticker ->
            allMetadata[ticker]?.let { ticker to it }
        }.toMap()
    }

    fun findUnknownTickers(tickers: Set<String>): Set<String> {
        return tickers - allPrices.keys
    }

    val availableTickers: Set<String>
        get() = allPrices.keys

    fun searchByQuery(query: String, limit: Int): List<TickerSearchHit> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        return allMetadata.entries.asSequence()
            .mapNotNull { (ticker, meta) ->
                val tickerLower = ticker.lowercase()
                val nameLower = meta.name.lowercase()
                val rank = when {
                    tickerLower == q -> 0
                    tickerLower.startsWith(q) -> 1
                    nameLower.startsWith(q) -> 2
                    nameLower.contains(q) || tickerLower.contains(q) -> 3
                    else -> return@mapNotNull null
                }
                TickerSearchHit(
                    symbol = ticker,
                    shortname = meta.name,
                    longname = meta.name,
                    exchange = exchangeFromSuffix(ticker),
                    quoteType = "EQUITY",
                    currency = meta.currency,
                    rank = rank,
                )
            }
            .sortedWith(compareBy({ it.rank }, { it.symbol }))
            .take(limit)
            .toList()
    }

    private fun exchangeFromSuffix(ticker: String): String? {
        val dot = ticker.lastIndexOf('.')
        if (dot < 0) return "NASDAQ"
        return when (ticker.substring(dot + 1).uppercase()) {
            "L" -> "LSE"
            "TO" -> "TSX"
            "MX" -> "BMV"
            "AS" -> "Euronext Amsterdam"
            "PA" -> "Euronext Paris"
            "DE" -> "XETRA"
            "HE" -> "Helsinki"
            "T" -> "Tokyo"
            "HK" -> "Hong Kong"
            else -> null
        }
    }
}

data class TickerSearchHit(
    val symbol: String,
    val shortname: String?,
    val longname: String?,
    val exchange: String?,
    val quoteType: String?,
    val currency: String?,
    val rank: Int,
)
