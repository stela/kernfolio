package com.kernfolio.fixtures

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.jacksonObjectMapper

/**
 * Plain JUnit 5 tests — no Spring context, no database.
 * Validates that all Block 0 fixture files parse correctly.
 *
 * Note: Jackson 3.x JsonNode has its own map/filter/forEach methods that shadow
 * Kotlin extensions. Use .toList() to get a Kotlin List<JsonNode> first.
 */
class FixtureDeserializationTest {

    private val mapper = jacksonObjectMapper()

    private fun loadFixture(name: String): String {
        val stream = javaClass.classLoader.getResourceAsStream("fixtures/$name")
            ?: throw AssertionError("Fixture not found: fixtures/$name")
        return stream.bufferedReader().readText()
    }

    private fun loadFixtureJson(name: String): JsonNode =
        mapper.readTree(loadFixture(name))

    /** Convert Jackson ArrayNode to Kotlin List to avoid Jackson's own map/filter methods. */
    private fun JsonNode.asList(): List<JsonNode> = buildList { this@asList.forEach { add(it) } }

    // ── Portfolio JSON ─────────────────────────────────────────────────

    @Test
    fun `portfolio JSON deserializes`() {
        val root = loadFixtureJson("test-portfolio.json")
        assertTrue(root.has("positions"))
        assertTrue(root.has("metadata"))
    }

    @Test
    fun `portfolio has 37 positions`() {
        val root = loadFixtureJson("test-portfolio.json")
        assertEquals(37, root["positions"].size())
    }

    @Test
    fun `portfolio weights sum to approximately 100`() {
        val root = loadFixtureJson("test-portfolio.json")
        val positions = root["positions"].asList()
        val total = positions.sumOf { it["weight_pct"].asDouble() }
        assertTrue(total in 99.0..101.0, "Weights sum to $total, expected ~100")
    }

    @Test
    fun `portfolio contains all 7 currencies`() {
        val root = loadFixtureJson("test-portfolio.json")
        val currencies = root["positions"].asList()
            .map { it["currency"].stringValue() }
            .toSet()
        val expected = setOf("CAD", "EUR", "GBP", "HKD", "JPY", "MXN", "USD")
        assertEquals(expected, currencies)
    }

    @Test
    fun `portfolio cash positions have null yfinance_ticker`() {
        val root = loadFixtureJson("test-portfolio.json")
        val cash = root["positions"].asList().filter { it["position_type"].stringValue() == "CASH" }
        assertEquals(4, cash.size)
        for (p in cash) {
            assertTrue(p["ticker"].stringValue().startsWith("CASH."))
            assertTrue(p["yfinance_ticker"].isNull)
        }
    }

    // ── Prices CSV ─────────────────────────────────────────────────────

    @Test
    fun `prices CSV parses with 33 ticker columns`() {
        val text = loadFixture("prices.csv")
        val lines = text.trim().lines()
        val header = lines.first().split(",")
        assertEquals("date", header[0])
        assertEquals(34, header.size, "Expected date + 33 tickers")
        assertTrue(lines.size > 750, "Expected >750 data rows, got ${lines.size - 1}")
    }

    @Test
    fun `prices CSV has no empty cells`() {
        val text = loadFixture("prices.csv")
        val lines = text.trim().lines()
        for ((i, line) in lines.withIndex()) {
            val cells = line.split(",")
            cells.forEachIndexed { col, cell ->
                assertTrue(cell.isNotBlank(), "Empty cell at row $i, col $col")
            }
        }
    }

    // ── FX Rates JSON ──────────────────────────────────────────────────

    @Test
    fun `fx rates JSON deserializes`() {
        val root = loadFixtureJson("fx-rates.json")
        assertTrue(root.has("rates"))
        assertTrue(root["rates"].size() >= 750)
    }

    @Test
    fun `fx rates contain all 6 target currencies`() {
        val root = loadFixtureJson("fx-rates.json")
        val ratesNode = root["rates"] as ObjectNode
        val firstDate = ratesNode.propertyNames().first()
        val currencies = (ratesNode[firstDate] as ObjectNode).propertyNames().toSet()
        val expected = setOf("USD", "CAD", "JPY", "GBP", "MXN", "HKD")
        assertEquals(expected, currencies)
    }

    // ── Fetch-prices response ──────────────────────────────────────────

    @Test
    fun `fetch-prices response matches schema`() {
        val root = loadFixtureJson("fetch-prices-response.json")
        assertTrue(root.has("prices"))
        assertTrue(root.has("metadata"))
        assertTrue(root.has("errors"))
        assertEquals(33, root["prices"].size())
        assertEquals(33, root["metadata"].size())
    }

    // ── Optimize request ───────────────────────────────────────────────

    @Test
    fun `optimize request matches schema`() {
        val root = loadFixtureJson("optimize-request.json")
        assertEquals("BLACK_LITTERMAN", root["algorithm"].stringValue())
        assertTrue(root.has("prices"))
        assertTrue(root["prices"].has("dates"))
        assertTrue(root.has("market_caps"))
        assertTrue(root.has("views"))
        assertTrue(root.has("confidences"))
        assertTrue(root.has("constraints"))
        assertEquals("ledoit_wolf", root["covariance_method"].stringValue())
    }

    // ── Golden optimize response ───────────────────────────────────────

    @Test
    fun `golden response matches schema`() {
        val root = loadFixtureJson("golden-optimize-bl-response.json")
        assertTrue(root.has("weights"))
        assertTrue(root.has("metrics"))
        assertTrue(root.has("efficient_frontier"))
        assertTrue(root.has("correlation_matrix"))
        assertTrue(root.has("computation_ms"))
    }

    @Test
    fun `golden weights sum to approximately 1`() {
        val root = loadFixtureJson("golden-optimize-bl-response.json")
        val weightsNode = root["weights"] as ObjectNode
        val total = weightsNode.properties().sumOf { it.value.asDouble() }
        assertTrue(total in 0.999..1.001, "Weights sum to $total, expected ~1.0")
    }

    @Test
    fun `golden efficient frontier has 50 points`() {
        val root = loadFixtureJson("golden-optimize-bl-response.json")
        assertEquals(50, root["efficient_frontier"].size())
        for (point in root["efficient_frontier"].asList()) {
            assertTrue(point.has("risk"))
            assertTrue(point.has("return"))
        }
    }

    // ── WireMock stubs ─────────────────────────────────────────────────

    @Test
    fun `wiremock mapping files are valid JSON`() {
        val mappings = listOf(
            "wiremock/mappings/optimize-200.json",
            "wiremock/mappings/optimize-422.json",
            "wiremock/mappings/fetch-prices-200.json",
            "wiremock/mappings/fetch-fx-rates-200.json",
        )
        for (mapping in mappings) {
            val root = loadFixtureJson(mapping)
            assertTrue(root.has("request"), "$mapping missing 'request'")
            assertTrue(root.has("response"), "$mapping missing 'response'")
        }
    }

    @Test
    fun `wiremock response body files are valid JSON`() {
        val files = listOf(
            "wiremock/__files/optimize-response.json",
            "wiremock/__files/optimize-error-response.json",
            "wiremock/__files/fetch-prices-response.json",
            "wiremock/__files/fetch-fx-rates-response.json",
        )
        for (file in files) {
            val root = loadFixtureJson(file)
            assertTrue(root.size() > 0, "$file is empty")
        }
    }
}
