package com.kernfolio.marketdata

import com.kernfolio.marketdata.dto.FetchFxRatesRequest
import com.kernfolio.marketdata.dto.FetchFxRatesResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry
import java.io.IOException
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeoutException

@Component
class FrankfurterClient(
    private val optimizerWebClient: WebClient,
) {
    private val log = LoggerFactory.getLogger(FrankfurterClient::class.java)

    // Retry only on what's plausibly transient: connection/read failures
    // and server-side (5xx) responses. Client errors (4xx) are terminal —
    // retrying a bad currency code won't help. Bounded to 2 retries so
    // total worst-case latency stays inside the Tomcat request timeout
    // (1s + 2s backoff ≈ 3s + base call time).
    private val retrySpec: Retry = Retry
        .backoff(MAX_RETRIES, Duration.ofSeconds(1))
        .filter { cause ->
            when (cause) {
                is WebClientResponseException -> cause.statusCode.is5xxServerError
                is WebClientRequestException, is IOException, is TimeoutException -> true
                else -> false
            }
        }
        .onRetryExhaustedThrow { _, signal -> signal.failure() }

    fun fetchFxRates(
        base: String,
        currencies: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): FetchFxRatesResponse {
        log.info("Fetching FX rates for {} currencies from {} to {}", currencies.size, startDate, endDate)

        try {
            return optimizerWebClient.post()
                .uri("/fetch-fx-rates")
                .bodyValue(FetchFxRatesRequest(base, currencies, startDate, endDate))
                .retrieve()
                .bodyToMono(FetchFxRatesResponse::class.java)
                .retryWhen(retrySpec)
                .block()
                ?: throw MarketDataException("Empty response from /fetch-fx-rates")
        } catch (e: WebClientResponseException) {
            throw MarketDataException("Failed to fetch FX rates: ${e.statusCode}", e)
        } catch (e: MarketDataException) {
            throw e
        } catch (e: Exception) {
            throw MarketDataException("Failed to fetch FX rates", e)
        }
    }

    companion object {
        private const val MAX_RETRIES = 2L
    }
}
