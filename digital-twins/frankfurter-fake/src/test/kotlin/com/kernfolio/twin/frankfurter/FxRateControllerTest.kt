package com.kernfolio.twin.frankfurter

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestClient
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

@SpringBootTest(webEnvironment = RANDOM_PORT)
class FxRateControllerTest(
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
    fun `returns rates for date range and selected currencies`() {
        val body = fetchRates("/v1/2023-03-15..2023-03-20?from=EUR&to=USD,CAD")

        assertThat(body["amount"].toString().toBigDecimal()).isEqualByComparingTo(BigDecimal.ONE)
        assertThat(body["base"]).isEqualTo("EUR")
        assertThat(body["start_date"]).isEqualTo("2023-03-15")
        assertThat(body["end_date"]).isEqualTo("2023-03-20")

        @Suppress("UNCHECKED_CAST")
        val rates = body["rates"] as Map<String, Map<String, Any>>
        assertThat(rates).isNotEmpty
        rates.values.forEach { currencies ->
            assertThat(currencies.keys).containsExactlyInAnyOrder("USD", "CAD")
        }
    }

    @Test
    fun `filters dates to requested range`() {
        val body = fetchRates("/v1/2023-03-15..2023-03-17?from=EUR&to=USD")

        @Suppress("UNCHECKED_CAST")
        val rates = body["rates"] as Map<String, Any>
        rates.keys.forEach { date ->
            assertThat(date).isGreaterThanOrEqualTo("2023-03-15")
            assertThat(date).isLessThanOrEqualTo("2023-03-17")
        }
    }

    @Test
    fun `returns all currencies when to param is omitted`() {
        val body = fetchRates("/v1/2023-03-15..2023-03-15?from=EUR")

        @Suppress("UNCHECKED_CAST")
        val rates = body["rates"] as Map<String, Map<String, Any>>
        assertThat(rates).hasSize(1)
        val currencies = rates.values.first().keys
        assertThat(currencies).containsExactlyInAnyOrder("USD", "CAD", "JPY", "GBP", "MXN", "HKD")
    }

    @Test
    fun `rejects non-EUR base currency`() {
        val response = client.get()
            .uri("/v1/2023-03-15..2023-03-20?from=USD&to=CAD")
            .exchange { _, res -> res.statusCode }

        assertThat(response.value()).isEqualTo(400)
    }

    @Test
    fun `rejects invalid date range format`() {
        val response = client.get()
            .uri("/v1/2023-03-15?from=EUR&to=USD")
            .exchange { _, res -> res.statusCode }

        assertThat(response.value()).isEqualTo(400)
    }

    @Test
    fun `health endpoint returns ok`() {
        val body = fetchRates("/health")
        assertThat(body["status"]).isEqualTo("ok")
    }

    private fun fetchRates(uri: String): Map<String, Any> {
        val body = client.get()
            .uri(uri)
            .retrieve()
            .body(String::class.java)!!
        val typeRef = object : TypeReference<Map<String, Any>>() {}
        return jsonMapper.readValue(body, typeRef)
    }
}
