package com.kernfolio.controller.api

import com.kernfolio.service.MarketDataService
import com.kernfolio.service.TickerSuggestion
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class InstrumentSearchControllerTest {

    private val marketDataService = mockk<MarketDataService>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(InstrumentSearchController(marketDataService))
            .build()
    }

    @Test
    fun `returns suggestions for trimmed query`() {
        every { marketDataService.searchInstruments("apple", 10) } returns listOf(
            TickerSuggestion("AAPL", "Apple Inc.", "NASDAQ", "USD", "Technology", "local"),
            TickerSuggestion("AAPLW", "Apple Warrants", null, "USD", null, "remote"),
        )

        mockMvc.perform(
            get("/api/instruments/search")
                .param("q", "  apple  ")
                .accept(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].ticker").value("AAPL"))
            .andExpect(jsonPath("$[0].name").value("Apple Inc."))
            .andExpect(jsonPath("$[0].exchange").value("NASDAQ"))
            .andExpect(jsonPath("$[0].currency").value("USD"))
            .andExpect(jsonPath("$[0].sector").value("Technology"))
            .andExpect(jsonPath("$[0].source").value("local"))
            .andExpect(jsonPath("$[1].ticker").value("AAPLW"))
            .andExpect(jsonPath("$[1].source").value("remote"))
    }

    @Test
    fun `rejects blank query with 400`() {
        mockMvc.perform(
            get("/api/instruments/search")
                .param("q", "   ")
                .accept(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { marketDataService.searchInstruments(any(), any()) }
    }

    @Test
    fun `rejects query longer than 50 chars with 400`() {
        mockMvc.perform(
            get("/api/instruments/search")
                .param("q", "x".repeat(51))
                .accept(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isBadRequest)
        verify(exactly = 0) { marketDataService.searchInstruments(any(), any()) }
    }

    @Test
    fun `clamps limit into valid range`() {
        every { marketDataService.searchInstruments("apple", 20) } returns emptyList()

        mockMvc.perform(
            get("/api/instruments/search")
                .param("q", "apple")
                .param("limit", "9999")
                .accept(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
        verify { marketDataService.searchInstruments("apple", 20) }
    }

    @Test
    fun `clamps negative limit to 1`() {
        every { marketDataService.searchInstruments("apple", 1) } returns emptyList()

        mockMvc.perform(
            get("/api/instruments/search")
                .param("q", "apple")
                .param("limit", "-5")
                .accept(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
        verify { marketDataService.searchInstruments("apple", 1) }
    }
}
