package com.kernfolio.marketdata

import org.springframework.stereotype.Component

@Component
class TickerMapper {

    private val internalToYfinance: Map<String, String> = mapOf(
        "GMEXICOB" to "GMEXICOB.MX",
        "FAST" to "FAST.AS",
        "PRE" to "PRE.L",
        "H4N" to "H4N.HE",
        "HY9H" to "HY9H.DE",
        "FFH" to "FFH.TO",
        "ANIC" to "ANIC.L",
        "8PSB" to "8PSB.L",
        "EGLN" to "EGLN.L",
        "DFND" to "DFND.L",
        "ODET" to "ODET.PA",
    )

    private val yfinanceToInternal: Map<String, String> =
        internalToYfinance.entries.associate { (k, v) -> v to k }

    fun toYfinance(internalTicker: String): String =
        internalToYfinance[internalTicker] ?: internalTicker

    fun toInternal(yfinanceTicker: String): String =
        yfinanceToInternal[yfinanceTicker] ?: yfinanceTicker

    fun toYfinanceAll(tickers: Collection<String>): List<String> =
        tickers.map { toYfinance(it) }
}
