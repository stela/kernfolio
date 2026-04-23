package com.kernfolio.marketdata

import com.kernfolio.marketdata.dto.FetchPricesRequest
import com.kernfolio.marketdata.dto.FetchPricesResponse
import com.kernfolio.marketdata.dto.SearchTickersRequest
import com.kernfolio.marketdata.dto.SearchTickersResponse
import com.kernfolio.marketdata.dto.TickerSearchResult
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.LocalDate

@Component
class YFinanceFetcher(
    private val optimizerWebClient: WebClient,
) {
    private val log = LoggerFactory.getLogger(YFinanceFetcher::class.java)

    fun fetchPrices(
        tickers: List<String>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): FetchPricesResponse {
        log.info("Fetching prices for {} tickers from {} to {}", tickers.size, startDate, endDate)

        try {
            return optimizerWebClient.post()
                .uri("/fetch-prices")
                .bodyValue(FetchPricesRequest(tickers, startDate, endDate))
                .retrieve()
                .bodyToMono(FetchPricesResponse::class.java)
                .block()
                ?: throw MarketDataException("Empty response from /fetch-prices")
        } catch (e: WebClientResponseException) {
            throw MarketDataException("Failed to fetch prices: ${e.statusCode}", e)
        } catch (e: MarketDataException) {
            throw e
        } catch (e: Exception) {
            throw MarketDataException("Failed to fetch prices", e)
        }
    }

    fun searchTickers(query: String, limit: Int): List<TickerSearchResult> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return emptyList()

        return try {
            optimizerWebClient.post()
                .uri("/search-tickers")
                .bodyValue(SearchTickersRequest(trimmed, limit))
                .retrieve()
                .bodyToMono(SearchTickersResponse::class.java)
                .block()
                ?.results
                ?: emptyList()
        } catch (e: Exception) {
            log.warn("Ticker search failed for query '{}': {}", trimmed, e.message)
            emptyList()
        }
    }
}
