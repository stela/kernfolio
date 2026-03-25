package com.kernfolio.marketdata

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import java.time.LocalDate

class YFinanceFetcherWireMockTest {

    companion object {
        private val wireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())

        @JvmStatic
        @BeforeAll
        fun startWireMock() {
            wireMockServer.start()
        }

        @JvmStatic
        @AfterAll
        fun stopWireMock() {
            wireMockServer.stop()
        }
    }

    private val webClient: WebClient by lazy {
        WebClient.builder()
            .baseUrl("http://localhost:${wireMockServer.port()}")
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }
            .build()
    }

    private val fetcher: YFinanceFetcher by lazy { YFinanceFetcher(webClient) }

    @BeforeEach
    fun resetStubs() {
        wireMockServer.resetAll()
    }

    @Test
    fun `deserializes fetch-prices response from fixture`() {
        val responseBody = javaClass.classLoader
            .getResourceAsStream("fixtures/wiremock/__files/fetch-prices-response.json")!!
            .bufferedReader().readText()

        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-prices"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)
                )
        )

        val response = fetcher.fetchPrices(
            tickers = listOf("GOOG", "8PSB.L"),
            startDate = LocalDate.of(2023, 3, 15),
            endDate = LocalDate.of(2026, 3, 15),
        )

        assertEquals(33, response.prices.size)
        assertEquals(33, response.metadata.size)
        assertTrue(response.errors.isEmpty())

        val googPrices = response.prices["GOOG"]
        assertNotNull(googPrices)
        assertTrue(googPrices!!.isNotEmpty())
        assertNotNull(googPrices.first().date)
        assertNotNull(googPrices.first().close)

        val googMeta = response.metadata["GOOG"]
        assertNotNull(googMeta)
        assertEquals("USD", googMeta!!.currency)
        assertNotNull(googMeta.name)
    }

    @Test
    fun `throws MarketDataException on server error`() {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-prices"))
                .willReturn(aResponse().withStatus(500))
        )

        assertThrows<MarketDataException> {
            fetcher.fetchPrices(
                tickers = listOf("GOOG"),
                startDate = LocalDate.of(2023, 3, 15),
                endDate = LocalDate.of(2026, 3, 15),
            )
        }
    }
}
