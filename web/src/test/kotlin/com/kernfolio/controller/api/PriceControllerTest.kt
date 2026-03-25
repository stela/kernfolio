package com.kernfolio.controller.api

import com.kernfolio.domain.CachedPrice
import com.kernfolio.service.MarketDataService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.LocalDate

class PriceControllerTest {

    private val marketDataService = mockk<MarketDataService>()
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc = MockMvcBuilders
            .standaloneSetup(PriceController(marketDataService))
            .build()
    }

    @Test
    fun `returns latest prices for requested tickers`() {
        val date = LocalDate.of(2025, 6, 15)
        every { marketDataService.getLatestPrices(listOf("GOOG", "AMZN")) } returns mapOf(
            "GOOG" to CachedPrice(
                ticker = "GOOG", priceDate = date,
                closePrice = BigDecimal("180.50"), currency = "USD",
            ),
            "AMZN" to CachedPrice(
                ticker = "AMZN", priceDate = date,
                closePrice = BigDecimal("195.20"), currency = "USD",
            ),
        )

        mockMvc.perform(
            get("/api/prices/latest")
                .param("tickers", "GOOG", "AMZN")
                .accept(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.GOOG.ticker").value("GOOG"))
            .andExpect(jsonPath("$.GOOG.close").value(180.50))
            .andExpect(jsonPath("$.GOOG.currency").value("USD"))
            .andExpect(jsonPath("$.AMZN.ticker").value("AMZN"))
    }

    @Test
    fun `returns null for tickers with no cached price`() {
        every { marketDataService.getLatestPrices(listOf("MISSING")) } returns mapOf(
            "MISSING" to null,
        )

        mockMvc.perform(
            get("/api/prices/latest")
                .param("tickers", "MISSING")
                .accept(MediaType.APPLICATION_JSON)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.MISSING").isEmpty)
    }
}
