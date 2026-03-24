package com.kernfolio.twin.yfinance

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.web.client.RestClient
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper

@SpringBootTest(webEnvironment = RANDOM_PORT)
class PriceControllerTest(
    @Autowired val jsonMapper: JsonMapper,
    @LocalServerPort val port: Int
) {

    lateinit var client: RestClient

    @BeforeEach
    fun setUp() {
        client = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()
    }

    @Test
    fun `returns prices and metadata for requested tickers`() {
        val body = postFetchPrices(
            """{"tickers":["GOOG","CSU.TO"],"start_date":"2023-03-15","end_date":"2023-03-20"}"""
        )

        @Suppress("UNCHECKED_CAST")
        val prices = body["prices"] as Map<String, List<Map<String, Any>>>
        assertThat(prices).containsKeys("GOOG", "CSU.TO")
        assertThat(prices["GOOG"]).isNotEmpty

        val firstPoint = prices["GOOG"]!!.first()
        assertThat(firstPoint["date"]).isEqualTo("2023-03-15")
        assertThat(firstPoint["close"]).isNotNull

        @Suppress("UNCHECKED_CAST")
        val metadata = body["metadata"] as Map<String, Map<String, Any>>
        assertThat(metadata).containsKeys("GOOG", "CSU.TO")
        assertThat(metadata["GOOG"]!!["currency"]).isEqualTo("USD")
        assertThat(metadata["CSU.TO"]!!["currency"]).isEqualTo("CAD")

        @Suppress("UNCHECKED_CAST")
        val errors = body["errors"] as Map<String, Any>
        assertThat(errors).isEmpty()
    }

    @Test
    fun `filters prices to requested date range`() {
        val body = postFetchPrices(
            """{"tickers":["GOOG"],"start_date":"2023-03-15","end_date":"2023-03-17"}"""
        )

        @Suppress("UNCHECKED_CAST")
        val prices = body["prices"] as Map<String, List<Map<String, Any>>>
        val dates = prices["GOOG"]!!.map { it["date"] as String }
        dates.forEach { date ->
            assertThat(date).isGreaterThanOrEqualTo("2023-03-15")
            assertThat(date).isLessThanOrEqualTo("2023-03-17")
        }
    }

    @Test
    fun `returns errors for unknown tickers`() {
        val body = postFetchPrices(
            """{"tickers":["GOOG","INVALID_TICKER"],"start_date":"2023-03-15","end_date":"2023-03-20"}"""
        )

        @Suppress("UNCHECKED_CAST")
        val prices = body["prices"] as Map<String, Any>
        assertThat(prices).containsKey("GOOG")
        assertThat(prices).doesNotContainKey("INVALID_TICKER")

        @Suppress("UNCHECKED_CAST")
        val errors = body["errors"] as Map<String, String>
        assertThat(errors).containsKey("INVALID_TICKER")
        assertThat(errors["INVALID_TICKER"]).contains("No data found")
    }

    @Test
    fun `rejects empty tickers list`() {
        val response = client.post()
            .uri("/fetch-prices")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"tickers":[],"start_date":"2023-03-15","end_date":"2023-03-20"}""")
            .exchange { _, res -> res.statusCode }

        assertThat(response.value()).isEqualTo(400)
    }

    @Test
    fun `rejects invalid date format`() {
        val response = client.post()
            .uri("/fetch-prices")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"tickers":["GOOG"],"start_date":"not-a-date","end_date":"2023-03-20"}""")
            .exchange { _, res -> res.statusCode }

        assertThat(response.value()).isEqualTo(400)
    }

    @Test
    fun `rejects end date before start date`() {
        val response = client.post()
            .uri("/fetch-prices")
            .contentType(MediaType.APPLICATION_JSON)
            .body("""{"tickers":["GOOG"],"start_date":"2023-03-20","end_date":"2023-03-15"}""")
            .exchange { _, res -> res.statusCode }

        assertThat(response.value()).isEqualTo(400)
    }

    @Test
    fun `health endpoint returns ok`() {
        val body = client.get()
            .uri("/health")
            .retrieve()
            .body(String::class.java)!!
        val typeRef = object : TypeReference<Map<String, Any>>() {}
        val result: Map<String, Any> = jsonMapper.readValue(body, typeRef)
        assertThat(result["status"]).isEqualTo("ok")
    }

    private fun postFetchPrices(json: String): Map<String, Any> {
        val body = client.post()
            .uri("/fetch-prices")
            .contentType(MediaType.APPLICATION_JSON)
            .body(json)
            .retrieve()
            .body(String::class.java)!!
        val typeRef = object : TypeReference<Map<String, Any>>() {}
        return jsonMapper.readValue(body, typeRef)
    }
}
