package com.kernfolio.repository

import com.kernfolio.TestcontainersConfiguration
import com.kernfolio.domain.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Transactional
class RepositoryIntegrationTest {

    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var inviteCodeRepository: InviteCodeRepository
    @Autowired lateinit var portfolioRepository: PortfolioRepository
    @Autowired lateinit var positionRepository: PositionRepository
    @Autowired lateinit var optimizationRunRepository: OptimizationRunRepository
    @Autowired lateinit var featureFlagRepository: FeatureFlagRepository
    @Autowired lateinit var cachedPriceRepository: CachedPriceRepository
    @Autowired lateinit var cachedFxRateRepository: CachedFxRateRepository
    @Autowired lateinit var instrumentRepository: InstrumentRepository

    private fun createUser(
        username: String = "testuser",
        email: String = "test@example.com",
    ): User = userRepository.save(
        User(username = username, email = email, passwordHash = "bcrypt_hash_123")
    )

    @Nested
    inner class UserRepositoryTests {

        @BeforeEach
        fun cleanup() {
            inviteCodeRepository.deleteAll()
            userRepository.deleteAll()
        }

        @Test
        fun `save and find by id`() {
            val saved = createUser()
            assertNotNull(saved.id)
            assertNotNull(saved.createdAt)

            val found = userRepository.findById(saved.id!!).orElse(null)
            assertNotNull(found)
            assertEquals("testuser", found.username)
        }

        @Test
        fun `find by username`() {
            createUser()
            val found = userRepository.findByUsername("testuser")
            assertNotNull(found)
            assertEquals("test@example.com", found!!.email)
        }

        @Test
        fun `find by email`() {
            createUser()
            val found = userRepository.findByEmail("test@example.com")
            assertNotNull(found)
            assertEquals("testuser", found!!.username)
        }

        @Test
        fun `find by username returns null for non-existent`() {
            assertNull(userRepository.findByUsername("nonexistent"))
        }

        @Test
        fun `default role is USER`() {
            val saved = createUser()
            assertEquals("USER", saved.role)
            assertTrue(saved.enabled)
        }
    }

    @Nested
    inner class InviteCodeRepositoryTests {

        @BeforeEach
        fun cleanup() {
            inviteCodeRepository.deleteAll()
            userRepository.deleteAll()
        }

        @Test
        fun `save and find by code`() {
            val admin = createUser(username = "admin", email = "admin@example.com")
            val saved = inviteCodeRepository.save(
                InviteCode(code = "INVITE123", createdBy = admin.id!!)
            )
            assertNotNull(saved.id)

            val found = inviteCodeRepository.findByCode("INVITE123")
            assertNotNull(found)
            assertEquals(admin.id, found!!.createdBy)
        }

        @Test
        fun `find all valid excludes used codes`() {
            val admin = createUser(username = "admin", email = "admin@example.com")
            val adminId = admin.id!!
            inviteCodeRepository.save(InviteCode(code = "VALID1", createdBy = adminId))
            inviteCodeRepository.save(
                InviteCode(code = "USED1", createdBy = adminId, usedBy = adminId)
            )

            val valid = inviteCodeRepository.findAllValid()
            assertEquals(1, valid.size)
            assertEquals("VALID1", valid[0].code)
        }
    }

    @Nested
    inner class PortfolioRepositoryTests {

        @BeforeEach
        fun cleanup() {
            portfolioRepository.deleteAll()
            inviteCodeRepository.deleteAll()
            userRepository.deleteAll()
        }

        @Test
        fun `save and find by user id`() {
            val user = createUser()
            val userId = user.id!!
            val saved = portfolioRepository.save(
                Portfolio(userId = userId, name = "My Portfolio", baseCurrency = "USD")
            )
            assertNotNull(saved.id)

            val portfolios = portfolioRepository.findByUserId(userId)
            assertEquals(1, portfolios.size)
            assertEquals("My Portfolio", portfolios[0].name)
            assertEquals("USD", portfolios[0].baseCurrency)
        }

        @Test
        fun `default base currency is EUR`() {
            val user = createUser()
            val saved = portfolioRepository.save(
                Portfolio(userId = user.id!!, name = "Default Currency")
            )
            assertEquals("EUR", saved.baseCurrency)
        }
    }

    @Nested
    inner class PositionRepositoryTests {

        @BeforeEach
        fun cleanup() {
            positionRepository.deleteAll()
            portfolioRepository.deleteAll()
            inviteCodeRepository.deleteAll()
            userRepository.deleteAll()
        }

        @Test
        fun `save and find by portfolio id`() {
            val user = createUser()
            val portfolio = portfolioRepository.save(Portfolio(userId = user.id!!, name = "Test"))
            val portfolioId = portfolio.id!!
            val saved = positionRepository.save(
                Position(
                    portfolioId = portfolioId,
                    ticker = "GOOG",
                    currency = "USD",
                    weightPct = BigDecimal("0.084000"),
                )
            )
            assertNotNull(saved.id)

            val positions = positionRepository.findByPortfolioId(portfolioId)
            assertEquals(1, positions.size)
            assertEquals("GOOG", positions[0].ticker)
            assertEquals(0, BigDecimal("0.084000").compareTo(positions[0].weightPct))
        }

        @Test
        fun `BigDecimal precision preserved`() {
            val user = createUser()
            val portfolio = portfolioRepository.save(Portfolio(userId = user.id!!, name = "Test"))
            val portfolioId = portfolio.id!!
            positionRepository.save(
                Position(
                    portfolioId = portfolioId,
                    ticker = "4256.T",
                    currency = "JPY",
                    weightPct = BigDecimal("0.023500"),
                    costBasisPct = BigDecimal("0.019200"),
                    intrinsicValueLocal = BigDecimal("3500.0000"),
                    confidencePct = BigDecimal("0.7500"),
                )
            )

            val positions = positionRepository.findByPortfolioId(portfolioId)
            val pos = positions[0]
            assertEquals(0, BigDecimal("0.023500").compareTo(pos.weightPct))
            assertEquals(0, BigDecimal("0.019200").compareTo(pos.costBasisPct))
            assertEquals(0, BigDecimal("3500.0000").compareTo(pos.intrinsicValueLocal))
            assertEquals(0, BigDecimal("0.7500").compareTo(pos.confidencePct))
        }

        @Test
        fun `delete by portfolio id`() {
            val user = createUser()
            val portfolio = portfolioRepository.save(Portfolio(userId = user.id!!, name = "Test"))
            val portfolioId = portfolio.id!!
            positionRepository.save(
                Position(portfolioId = portfolioId, ticker = "GOOG", currency = "USD", weightPct = BigDecimal("0.05"))
            )
            positionRepository.save(
                Position(portfolioId = portfolioId, ticker = "AMZN", currency = "USD", weightPct = BigDecimal("0.05"))
            )

            positionRepository.deleteByPortfolioId(portfolioId)
            assertTrue(positionRepository.findByPortfolioId(portfolioId).isEmpty())
        }
    }

    @Nested
    inner class OptimizationRunRepositoryTests {

        @BeforeEach
        fun cleanup() {
            optimizationRunRepository.deleteAll()
            portfolioRepository.deleteAll()
            inviteCodeRepository.deleteAll()
            userRepository.deleteAll()
        }

        @Test
        fun `save and read back JSONB round-trip`() {
            val user = createUser()
            val portfolio = portfolioRepository.save(Portfolio(userId = user.id!!, name = "Test"))

            val params = OptimizationParameters(
                riskFreeRate = 0.035,
                tau = 0.05,
                covarianceMethod = "ledoit_wolf",
                lookbackYears = 3,
                constraints = OptimizationConstraints(
                    minWeight = 0.01,
                    maxWeight = 0.08,
                    longOnly = true,
                    sectorMax = mapOf("Technology" to 0.25),
                    excludePositionTypes = listOf("CASH"),
                ),
            )
            val results = OptimizationResults(
                optimizedWeights = mapOf("GOOG" to 0.097, "AMZN" to 0.091),
                metrics = OptimizationMetrics(
                    expectedAnnualReturn = 0.108,
                    annualVolatility = 0.165,
                    sharpeRatio = 0.44,
                ),
                correlationClusters = listOf(
                    CorrelationCluster(
                        name = "US Tech",
                        tickers = listOf("GOOG", "AMZN"),
                        avgCorrelation = 0.65,
                        effectiveN = 1.36,
                    )
                ),
            )

            val saved = optimizationRunRepository.save(
                OptimizationRun(
                    portfolioId = portfolio.id!!,
                    algorithm = "BLACK_LITTERMAN",
                    parameters = params,
                    results = results,
                    computationMs = 142,
                )
            )
            assertNotNull(saved.id)

            val found = optimizationRunRepository.findById(saved.id!!).orElse(null)
            assertNotNull(found)
            assertEquals("BLACK_LITTERMAN", found.algorithm)
            assertEquals(0.035, found.parameters.riskFreeRate)
            assertEquals(0.01, found.parameters.constraints?.minWeight)
            assertEquals(listOf("CASH"), found.parameters.constraints?.excludePositionTypes)
            assertEquals(0.097, found.results.optimizedWeights["GOOG"])
            assertEquals(0.44, found.results.metrics?.sharpeRatio)
            assertEquals("US Tech", found.results.correlationClusters!![0].name)
            assertEquals(142, found.computationMs)
        }

        @Test
        fun `find recent by portfolio id`() {
            val user = createUser()
            val portfolio = portfolioRepository.save(Portfolio(userId = user.id!!, name = "Test"))
            val params = OptimizationParameters()
            val results = OptimizationResults()

            repeat(5) {
                optimizationRunRepository.save(
                    OptimizationRun(
                        portfolioId = portfolio.id!!,
                        algorithm = "MEAN_VARIANCE",
                        parameters = params,
                        results = results,
                    )
                )
            }

            val recent = optimizationRunRepository.findRecentByPortfolioId(portfolio.id!!, 3)
            assertEquals(3, recent.size)
        }
    }

    @Nested
    inner class FeatureFlagRepositoryTests {

        @BeforeEach
        fun cleanup() = featureFlagRepository.deleteAll()

        @Test
        fun `save and find by flag name`() {
            featureFlagRepository.save(
                FeatureFlag(
                    flagName = "ALGO_BLACK_LITTERMAN",
                    enabled = true,
                    description = "Black-Litterman algorithm",
                )
            )

            val found = featureFlagRepository.findByFlagName("ALGO_BLACK_LITTERMAN")
            assertNotNull(found)
            assertTrue(found!!.enabled)
        }

        @Test
        fun `UUID array round-trip`() {
            val userId1 = UUID.randomUUID()
            val userId2 = UUID.randomUUID()

            featureFlagRepository.save(
                FeatureFlag(
                    flagName = "BETA_FEATURE",
                    enabled = true,
                    allowedUserIds = listOf(userId1, userId2),
                    rolloutPct = 50,
                )
            )

            val found = featureFlagRepository.findByFlagName("BETA_FEATURE")
            assertNotNull(found)
            assertEquals(2, found!!.allowedUserIds.size)
            assertTrue(found.allowedUserIds.containsAll(listOf(userId1, userId2)))
            assertEquals(50, found.rolloutPct)
        }

        @Test
        fun `empty allowed user ids`() {
            featureFlagRepository.save(
                FeatureFlag(flagName = "GLOBAL_FLAG", enabled = true)
            )

            val found = featureFlagRepository.findByFlagName("GLOBAL_FLAG")
            assertNotNull(found)
            assertTrue(found!!.allowedUserIds.isEmpty())
        }
    }

    @Nested
    inner class CachedPriceRepositoryTests {

        @BeforeEach
        fun cleanup() {
            // Delete via upsert-based repo — need to clear with findAll + custom approach
            // Use the findAll then iterate — but we have no delete method.
            // For cleanup, we can just rely on each test using unique data.
        }

        @Test
        fun `upsert and find by ticker and date range`() {
            cachedPriceRepository.upsert("GOOG", LocalDate.of(2024, 1, 2), BigDecimal("140.2500"), "USD")
            cachedPriceRepository.upsert("GOOG", LocalDate.of(2024, 1, 3), BigDecimal("141.5000"), "USD")
            cachedPriceRepository.upsert("GOOG", LocalDate.of(2024, 1, 4), BigDecimal("139.7500"), "USD")

            val prices = cachedPriceRepository.findByTickerAndDateRange(
                "GOOG", LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3)
            )
            assertEquals(2, prices.size)
            assertEquals(0, BigDecimal("140.2500").compareTo(prices[0].closePrice))
        }

        @Test
        fun `upsert updates existing row`() {
            cachedPriceRepository.upsert("AMZN", LocalDate.of(2024, 6, 1), BigDecimal("180.0000"), "USD")
            cachedPriceRepository.upsert("AMZN", LocalDate.of(2024, 6, 1), BigDecimal("185.0000"), "USD")

            val latest = cachedPriceRepository.findLatestByTicker("AMZN")
            assertNotNull(latest)
            assertEquals(0, BigDecimal("185.0000").compareTo(latest!!.closePrice))
        }

        @Test
        fun `find latest by ticker`() {
            cachedPriceRepository.upsert("NVDA", LocalDate.of(2024, 1, 1), BigDecimal("50.00"), "USD")
            cachedPriceRepository.upsert("NVDA", LocalDate.of(2024, 6, 1), BigDecimal("120.00"), "USD")

            val latest = cachedPriceRepository.findLatestByTicker("NVDA")
            assertNotNull(latest)
            assertEquals(LocalDate.of(2024, 6, 1), latest!!.priceDate)
        }

        @Test
        fun `find max date by ticker`() {
            cachedPriceRepository.upsert("TSLA", LocalDate.of(2024, 3, 1), BigDecimal("200.00"), "USD")
            cachedPriceRepository.upsert("TSLA", LocalDate.of(2024, 5, 15), BigDecimal("180.00"), "USD")

            val maxDate = cachedPriceRepository.findMaxDateByTicker("TSLA")
            assertEquals(LocalDate.of(2024, 5, 15), maxDate)
        }

        @Test
        fun `find by multiple tickers and date range`() {
            cachedPriceRepository.upsert("BRK.B", LocalDate.of(2024, 2, 1), BigDecimal("370.00"), "USD")
            cachedPriceRepository.upsert("FFH", LocalDate.of(2024, 2, 1), BigDecimal("1200.00"), "CAD")

            val prices = cachedPriceRepository.findByTickersAndDateRange(
                listOf("BRK.B", "FFH"), LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31)
            )
            assertEquals(2, prices.size)
        }
    }

    @Nested
    inner class CachedFxRateRepositoryTests {

        @Test
        fun `upsert and find by currency pair and date range`() {
            cachedFxRateRepository.upsert("EURUSD", LocalDate.of(2024, 1, 2), BigDecimal("1.08500000"))
            cachedFxRateRepository.upsert("EURUSD", LocalDate.of(2024, 1, 3), BigDecimal("1.09200000"))

            val rates = cachedFxRateRepository.findByCurrencyPairAndDateRange(
                "EURUSD", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 5)
            )
            assertEquals(2, rates.size)
        }

        @Test
        fun `upsert updates existing rate`() {
            cachedFxRateRepository.upsert("EURJPY", LocalDate.of(2024, 3, 1), BigDecimal("162.50000000"))
            cachedFxRateRepository.upsert("EURJPY", LocalDate.of(2024, 3, 1), BigDecimal("163.00000000"))

            val latest = cachedFxRateRepository.findLatestByCurrencyPair("EURJPY")
            assertNotNull(latest)
            assertEquals(0, BigDecimal("163.00000000").compareTo(latest!!.rate))
        }

        @Test
        fun `find latest by currency pair`() {
            cachedFxRateRepository.upsert("EURCAD", LocalDate.of(2024, 1, 1), BigDecimal("1.46000000"))
            cachedFxRateRepository.upsert("EURCAD", LocalDate.of(2024, 6, 1), BigDecimal("1.48000000"))

            val latest = cachedFxRateRepository.findLatestByCurrencyPair("EURCAD")
            assertEquals(LocalDate.of(2024, 6, 1), latest!!.rateDate)
        }
    }

    @Nested
    inner class InstrumentRepositoryTests {

        @BeforeEach
        fun cleanup() = instrumentRepository.deleteAll()

        @Test
        fun `save and find by id`() {
            instrumentRepository.save(
                Instrument(
                    ticker = "GOOG",
                    name = "Alphabet Inc.",
                    exchange = "NASDAQ",
                    currency = "USD",
                    sector = "Technology",
                    marketCapUsd = BigDecimal("1850000000000.00"),
                )
            )

            val found = instrumentRepository.findById("GOOG").orElse(null)
            assertNotNull(found)
            assertEquals("Alphabet Inc.", found.name)
            assertEquals("Technology", found.sector)
        }

        @Test
        fun `find by multiple tickers`() {
            instrumentRepository.save(Instrument(ticker = "GOOG", name = "Alphabet Inc.", currency = "USD"))
            instrumentRepository.save(Instrument(ticker = "AMZN", name = "Amazon.com Inc.", currency = "USD"))
            instrumentRepository.save(Instrument(ticker = "4256.T", name = "CYND Co., Ltd.", currency = "JPY"))

            val found = instrumentRepository.findByTickers(listOf("GOOG", "4256.T"))
            assertEquals(2, found.size)
        }

        @Test
        fun `update existing instrument`() {
            instrumentRepository.save(Instrument(ticker = "NVDA", name = "NVIDIA Corp.", currency = "USD"))

            val updated = Instrument(ticker = "NVDA", name = "NVIDIA Corp.", currency = "USD", sector = "Technology")
                .markNotNew()
            instrumentRepository.save(updated)

            val found = instrumentRepository.findById("NVDA").orElse(null)
            assertEquals("Technology", found!!.sector)
        }

        @Test
        fun `search ranks exact ticker before prefix before name-substring`() {
            instrumentRepository.save(
                Instrument(ticker = "AAPL", name = "Apple Inc.", currency = "USD",
                    marketCapUsd = BigDecimal("3000000000000"))
            )
            instrumentRepository.save(
                Instrument(ticker = "AAPLW", name = "Apple Warrants", currency = "USD",
                    marketCapUsd = BigDecimal("1000000"))
            )
            instrumentRepository.save(
                Instrument(ticker = "SOMEOTHER", name = "Fresh Apple Ltd.", currency = "USD",
                    marketCapUsd = BigDecimal("5000000"))
            )
            instrumentRepository.save(
                Instrument(ticker = "UNRELATED", name = "Unrelated Ltd.", currency = "USD",
                    marketCapUsd = BigDecimal("100000"))
            )

            val results = instrumentRepository.search("AAPL", 10)
            val tickers = results.map { it.ticker }
            assertEquals(listOf("AAPL", "AAPLW"), tickers)
        }

        @Test
        fun `search matches by name substring case-insensitively`() {
            instrumentRepository.save(Instrument(ticker = "AMZN", name = "Amazon.com Inc.", currency = "USD"))
            instrumentRepository.save(Instrument(ticker = "GOOG", name = "Alphabet Inc.", currency = "USD"))

            val results = instrumentRepository.search("alphabet", 10)
            assertEquals(listOf("GOOG"), results.map { it.ticker })
        }

        @Test
        fun `search orders ties by market cap desc`() {
            instrumentRepository.save(
                Instrument(ticker = "FOO1", name = "Foo One", currency = "USD",
                    marketCapUsd = BigDecimal("1000"))
            )
            instrumentRepository.save(
                Instrument(ticker = "FOO2", name = "Foo Two", currency = "USD",
                    marketCapUsd = BigDecimal("9000"))
            )

            val results = instrumentRepository.search("FOO", 10)
            assertEquals(listOf("FOO2", "FOO1"), results.map { it.ticker })
        }

        @Test
        fun `search honours limit`() {
            repeat(5) { i ->
                instrumentRepository.save(
                    Instrument(ticker = "BAR$i", name = "Bar $i", currency = "USD",
                        marketCapUsd = BigDecimal(i.toLong()))
                )
            }

            val results = instrumentRepository.search("BAR", 2)
            assertEquals(2, results.size)
        }
    }
}
