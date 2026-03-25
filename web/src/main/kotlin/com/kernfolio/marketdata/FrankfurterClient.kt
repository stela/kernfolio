package com.kernfolio.marketdata

import com.kernfolio.marketdata.dto.FetchFxRatesRequest
import com.kernfolio.marketdata.dto.FetchFxRatesResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.LocalDate

@Component
class FrankfurterClient(
    private val optimizerWebClient: WebClient,
) {
    private val log = LoggerFactory.getLogger(FrankfurterClient::class.java)

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
}
