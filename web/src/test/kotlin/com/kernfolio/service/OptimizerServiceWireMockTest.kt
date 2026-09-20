package com.kernfolio.service

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.ContainsPattern
import com.kernfolio.domain.CachedPrice
import com.kernfolio.domain.Instrument
import com.kernfolio.domain.OptimizationRun
import com.kernfolio.domain.Portfolio
import com.kernfolio.domain.Position
import com.kernfolio.marketdata.TickerMapper
import com.kernfolio.repository.CachedPriceRepository
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.OptimizationRunRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class OptimizerServiceWireMockTest {

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

    private val portfolioService = mockk<PortfolioService>()
    private val cachedPriceRepository = mockk<CachedPriceRepository>()
    private val instrumentRepository = mockk<InstrumentRepository>()
    private val currencyConversionService = mockk<CurrencyConversionService>()
    private val fxRateService = mockk<FxRateService>(relaxed = true)
    private val tickerMapper = TickerMapper()
    private val optimizationRunRepository = mockk<OptimizationRunRepository> {
        every { save(any<OptimizationRun>()) } answers { firstArg() }
    }
    private val objectMapper = JsonMapper.builder().build()

    private val service: OptimizerService by lazy {
        OptimizerService(
            portfolioService, cachedPriceRepository, instrumentRepository,
            currencyConversionService, fxRateService, tickerMapper, optimizationRunRepository,
            webClient, objectMapper,
        )
    }

    private val userId = UUID.randomUUID()
    private val portfolioId = UUID.randomUUID()
    private val portfolio = Portfolio(id = portfolioId, userId = userId, name = "Test", baseCurrency = "EUR")

    @BeforeEach
    fun resetStubs() {
        wireMockServer.resetAll()
    }

    private fun setupPositionsAndPrices() {
        every { portfolioService.findByIdAndUserId(portfolioId, userId) } returns portfolio
        every { portfolioService.findPositionsByPortfolioId(portfolioId, userId) } returns listOf(
            Position(
                id = UUID.randomUUID(), portfolioId = portfolioId,
                ticker = "GOOG", currency = "USD", weightPct = BigDecimal("0.05"),
                expectedReturn = BigDecimal("0.12"), returnStddev = BigDecimal("0.30"),
                sector = "Technology",
            ),
            Position(
                id = UUID.randomUUID(), portfolioId = portfolioId,
                ticker = "BRK.B", currency = "USD", weightPct = BigDecimal("0.03"),
                sector = "Financials",
            ),
        )

        val dates = (1..10).map { LocalDate.of(2025, 1, it) }
        val prices = listOf("GOOG", "BRK.B").flatMap { ticker ->
            dates.map { date ->
                CachedPrice(ticker = ticker, priceDate = date, closePrice = BigDecimal("100.00"), currency = "USD")
            }
        }
        every { cachedPriceRepository.findByTickersAndDateRange(any(), any(), any()) } returns prices
        every { currencyConversionService.convertTo(any(), eq("USD"), eq("EUR")) } answers {
            (firstArg<List<CachedPrice>>()).map { it.priceDate to it.closePrice }
        }
        every { cachedPriceRepository.findLatestByTicker("GOOG") } returns
            CachedPrice(ticker = "GOOG", priceDate = LocalDate.now(), closePrice = BigDecimal("100.00"), currency = "USD")
        every { cachedPriceRepository.findLatestByTicker("BRK.B") } returns
            CachedPrice(ticker = "BRK.B", priceDate = LocalDate.now(), closePrice = BigDecimal("100.00"), currency = "USD")
        every { instrumentRepository.findByTickers(any()) } returns listOf(
            Instrument(ticker = "GOOG", currency = "USD", marketCapUsd = BigDecimal("1850000000000")),
            Instrument(ticker = "BRK.B", currency = "USD", marketCapUsd = BigDecimal("900000000000")),
        )
    }

    @Test
    fun `full round-trip optimization returns COMPLETED run`() {
        val responseBody = javaClass.classLoader
            .getResourceAsStream("fixtures/wiremock/__files/optimize-response.json")!!
            .bufferedReader().readText()

        wireMockServer.stubFor(
            post(urlPathEqualTo("/optimize"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)
                )
        )

        setupPositionsAndPrices()

        val result = service.optimize(portfolioId, userId, "black_litterman")

        assertEquals("COMPLETED", result.status)
        assertNotNull(result.results.metrics)
        assertTrue(result.results.optimizedWeights.isNotEmpty())
        assertEquals("black_litterman", result.algorithm)

        // Verify WireMock received the request
        wireMockServer.verify(
            postRequestedFor(urlPathEqualTo("/optimize"))
                .withRequestBody(ContainsPattern("\"algorithm\""))
                .withRequestBody(ContainsPattern("\"dates\""))
        )

        // Verify result was saved
        verify { optimizationRunRepository.save(match<OptimizationRun> { it.status == "COMPLETED" }) }
    }

    @Test
    fun `optimizer error returns FAILED run and throws`() {
        val errorBody = javaClass.classLoader
            .getResourceAsStream("fixtures/wiremock/__files/optimize-error-response.json")!!
            .bufferedReader().readText()

        wireMockServer.stubFor(
            post(urlPathEqualTo("/optimize"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody(errorBody)
                )
        )

        setupPositionsAndPrices()

        val ex = assertThrows<OptimizationException> {
            service.optimize(portfolioId, userId, "black_litterman")
        }

        assertTrue(ex.message!!.contains("singular") || ex.message!!.contains("Optimizer returned"))

        verify {
            optimizationRunRepository.save(match<OptimizationRun> {
                it.status == "FAILED" && it.errorMessage != null
            })
        }
    }
}
