package com.kernfolio.marketdata

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import org.junit.jupiter.api.AfterAll
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

class FrankfurterClientWireMockTest {

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
            .build()
    }

    private val client: FrankfurterClient by lazy { FrankfurterClient(webClient) }

    @BeforeEach
    fun resetStubs() {
        wireMockServer.resetAll()
    }

    @Test
    fun `deserializes fetch-fx-rates response from fixture`() {
        val responseBody = javaClass.classLoader
            .getResourceAsStream("fixtures/wiremock/__files/fetch-fx-rates-response.json")!!
            .bufferedReader().readText()

        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-fx-rates"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)
                )
        )

        val response = client.fetchFxRates(
            base = "EUR",
            currencies = listOf("USD", "CAD", "JPY", "GBP", "MXN", "HKD"),
            startDate = LocalDate.of(2023, 3, 15),
            endDate = LocalDate.of(2026, 3, 15),
        )

        assertTrue(response.rates.isNotEmpty())

        val firstDate = response.rates.keys.min()
        assertNotNull(firstDate)
        val firstRates = response.rates[firstDate]!!
        assertTrue(firstRates.containsKey("USD"))
        assertTrue(firstRates.containsKey("JPY"))
        assertTrue(firstRates["USD"]!! > java.math.BigDecimal.ZERO)
    }

    @Test
    fun `throws MarketDataException on server error`() {
        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-fx-rates"))
                .willReturn(aResponse().withStatus(500))
        )

        assertThrows<MarketDataException> {
            client.fetchFxRates(
                base = "EUR",
                currencies = listOf("USD"),
                startDate = LocalDate.of(2023, 3, 15),
                endDate = LocalDate.of(2026, 3, 15),
            )
        }
    }
}
