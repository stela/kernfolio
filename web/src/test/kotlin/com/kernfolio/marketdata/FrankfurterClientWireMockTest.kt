package com.kernfolio.marketdata

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.stubbing.Scenario
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

    @Test
    fun `retries on 5xx and succeeds on the third attempt`() {
        // Frankfurter is a free public API; transient 5xx is plausible
        // under load. Two retries give us 1s + 2s of backoff and should
        // tide us over a short hiccup.
        val successBody = javaClass.classLoader
            .getResourceAsStream("fixtures/wiremock/__files/fetch-fx-rates-response.json")!!
            .bufferedReader().readText()

        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-fx-rates"))
                .inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("one-failure")
        )
        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-fx-rates"))
                .inScenario("retry")
                .whenScenarioStateIs("one-failure")
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("two-failures")
        )
        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-fx-rates"))
                .inScenario("retry")
                .whenScenarioStateIs("two-failures")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(successBody)
                )
        )

        val response = client.fetchFxRates(
            base = "EUR",
            currencies = listOf("USD"),
            startDate = LocalDate.of(2025, 1, 1),
            endDate = LocalDate.of(2025, 1, 2),
        )

        assertTrue(response.rates.isNotEmpty())
        // Initial attempt + 2 retries = 3 total requests
        wireMockServer.verify(3, postRequestedFor(urlPathEqualTo("/fetch-fx-rates")))
    }

    @Test
    fun `does not retry on 4xx client error`() {
        // A bad request (e.g. unknown currency code) is terminal. Retrying
        // wastes cycles and spams a free public API with something that
        // will never succeed.
        wireMockServer.stubFor(
            post(urlPathEqualTo("/fetch-fx-rates"))
                .willReturn(aResponse().withStatus(400))
        )

        assertThrows<MarketDataException> {
            client.fetchFxRates(
                base = "EUR",
                currencies = listOf("ZZZ"),
                startDate = LocalDate.of(2025, 1, 1),
                endDate = LocalDate.of(2025, 1, 2),
            )
        }

        wireMockServer.verify(1, postRequestedFor(urlPathEqualTo("/fetch-fx-rates")))
    }
}
