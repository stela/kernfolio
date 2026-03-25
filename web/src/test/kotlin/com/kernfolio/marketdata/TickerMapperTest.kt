package com.kernfolio.marketdata

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TickerMapperTest {

    private val mapper = TickerMapper()

    @Test
    fun `maps internal tickers to yfinance format`() {
        assertEquals("GMEXICOB.MX", mapper.toYfinance("GMEXICOB"))
        assertEquals("FAST.AS", mapper.toYfinance("FAST"))
        assertEquals("PRE.L", mapper.toYfinance("PRE"))
        assertEquals("H4N.HE", mapper.toYfinance("H4N"))
        assertEquals("HY9H.DE", mapper.toYfinance("HY9H"))
        assertEquals("FFH.TO", mapper.toYfinance("FFH"))
        assertEquals("ANIC.L", mapper.toYfinance("ANIC"))
        assertEquals("8PSB.L", mapper.toYfinance("8PSB"))
        assertEquals("EGLN.L", mapper.toYfinance("EGLN"))
        assertEquals("DFND.L", mapper.toYfinance("DFND"))
        assertEquals("ODET.PA", mapper.toYfinance("ODET"))
    }

    @Test
    fun `passes through tickers already in yfinance format`() {
        assertEquals("GOOG", mapper.toYfinance("GOOG"))
        assertEquals("CSU.TO", mapper.toYfinance("CSU.TO"))
        assertEquals("4256.T", mapper.toYfinance("4256.T"))
        assertEquals("3690.HK", mapper.toYfinance("3690.HK"))
        assertEquals("TEP.PA", mapper.toYfinance("TEP.PA"))
    }

    @Test
    fun `maps yfinance tickers back to internal format`() {
        assertEquals("GMEXICOB", mapper.toInternal("GMEXICOB.MX"))
        assertEquals("FAST", mapper.toInternal("FAST.AS"))
        assertEquals("DFND", mapper.toInternal("DFND.L"))
    }

    @Test
    fun `passes through yfinance tickers with no internal override`() {
        assertEquals("GOOG", mapper.toInternal("GOOG"))
        assertEquals("CSU.TO", mapper.toInternal("CSU.TO"))
        assertEquals("4256.T", mapper.toInternal("4256.T"))
    }

    @Test
    fun `round-trip preserves all mapped tickers`() {
        val mapped = listOf(
            "GMEXICOB", "FAST", "PRE", "H4N", "HY9H",
            "FFH", "ANIC", "8PSB", "EGLN", "DFND", "ODET",
        )
        for (ticker in mapped) {
            assertEquals(ticker, mapper.toInternal(mapper.toYfinance(ticker)))
        }
    }

    @Test
    fun `toYfinanceAll maps a collection`() {
        val result = mapper.toYfinanceAll(listOf("GOOG", "GMEXICOB", "FFH"))
        assertEquals(listOf("GOOG", "GMEXICOB.MX", "FFH.TO"), result)
    }
}
