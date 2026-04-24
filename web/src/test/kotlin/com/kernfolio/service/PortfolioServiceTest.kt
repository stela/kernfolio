package com.kernfolio.service

import com.kernfolio.domain.Instrument
import com.kernfolio.domain.Portfolio
import com.kernfolio.domain.Position
import com.kernfolio.repository.InstrumentRepository
import com.kernfolio.repository.PortfolioRepository
import com.kernfolio.repository.PositionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

class PortfolioServiceTest {

    private val portfolioRepository = mockk<PortfolioRepository>()
    private val positionRepository = mockk<PositionRepository>()
    private val instrumentRepository = mockk<InstrumentRepository>(relaxed = true)
    private lateinit var service: PortfolioService

    private val userId = UUID.randomUUID()
    private val otherUserId = UUID.randomUUID()
    private val portfolioId = UUID.randomUUID()

    private val portfolio = Portfolio(
        id = portfolioId,
        userId = userId,
        name = "Test Portfolio",
        description = "A test",
        baseCurrency = "EUR",
    )

    @BeforeEach
    fun setup() {
        service = PortfolioService(portfolioRepository, positionRepository, instrumentRepository)
    }

    @Nested
    inner class FindByUserId {
        @Test
        fun `delegates to repository`() {
            every { portfolioRepository.findByUserId(userId) } returns listOf(portfolio)
            assertEquals(listOf(portfolio), service.findByUserId(userId))
        }
    }

    @Nested
    inner class FindByIdAndUserId {
        @Test
        fun `returns portfolio when ownership matches`() {
            every { portfolioRepository.findById(portfolioId) } returns Optional.of(portfolio)
            assertEquals(portfolio, service.findByIdAndUserId(portfolioId, userId))
        }

        @Test
        fun `returns null when not found`() {
            every { portfolioRepository.findById(portfolioId) } returns Optional.empty()
            assertNull(service.findByIdAndUserId(portfolioId, userId))
        }

        @Test
        fun `returns null when userId does not match`() {
            every { portfolioRepository.findById(portfolioId) } returns Optional.of(portfolio)
            assertNull(service.findByIdAndUserId(portfolioId, otherUserId))
        }
    }

    @Nested
    inner class Create {
        @Test
        fun `saves portfolio with correct fields`() {
            val slot = slot<Portfolio>()
            every { portfolioRepository.save(capture(slot)) } answers { slot.captured.copy(id = UUID.randomUUID()) }

            service.create(userId, "My Portfolio", "desc", "USD")

            assertEquals(userId, slot.captured.userId)
            assertEquals("My Portfolio", slot.captured.name)
            assertEquals("desc", slot.captured.description)
            assertEquals("USD", slot.captured.baseCurrency)
        }
    }

    @Nested
    inner class Update {
        @Test
        fun `updates portfolio fields`() {
            val slot = slot<Portfolio>()
            every { portfolioRepository.findById(portfolioId) } returns Optional.of(portfolio)
            every { portfolioRepository.save(capture(slot)) } answers { slot.captured }

            service.update(portfolioId, userId, "New Name", "New desc", "USD")

            assertEquals("New Name", slot.captured.name)
            assertEquals("New desc", slot.captured.description)
            assertEquals("USD", slot.captured.baseCurrency)
        }

        @Test
        fun `throws when not found`() {
            every { portfolioRepository.findById(portfolioId) } returns Optional.empty()
            assertThrows<PortfolioNotFoundException> {
                service.update(portfolioId, userId, "x", null, "EUR")
            }
        }

        @Test
        fun `throws when userId does not match`() {
            every { portfolioRepository.findById(portfolioId) } returns Optional.of(portfolio)
            assertThrows<PortfolioNotFoundException> {
                service.update(portfolioId, otherUserId, "x", null, "EUR")
            }
        }
    }

    @Nested
    inner class Delete {
        @Test
        fun `deletes positions then portfolio`() {
            every { portfolioRepository.findById(portfolioId) } returns Optional.of(portfolio)
            every { positionRepository.deleteByPortfolioId(portfolioId) } returns Unit
            every { portfolioRepository.deleteById(portfolioId) } returns Unit

            service.delete(portfolioId, userId)

            verify(ordering = io.mockk.Ordering.ORDERED) {
                positionRepository.deleteByPortfolioId(portfolioId)
                portfolioRepository.deleteById(portfolioId)
            }
        }

        @Test
        fun `throws when not found`() {
            every { portfolioRepository.findById(portfolioId) } returns Optional.empty()
            assertThrows<PortfolioNotFoundException> {
                service.delete(portfolioId, userId)
            }
        }
    }

    @Nested
    inner class AddPosition {
        @Test
        fun `saves position with correct fields`() {
            val slot = slot<Position>()
            every { portfolioRepository.findById(portfolioId) } returns Optional.of(portfolio)
            every { positionRepository.save(capture(slot)) } answers { slot.captured.copy(id = UUID.randomUUID()) }

            val form = PositionForm(
                ticker = "GOOG",
                currency = "USD",
                weightPct = BigDecimal("0.15"),
                sector = "Tech",
            )
            service.addPosition(portfolioId, userId, form)

            assertEquals(portfolioId, slot.captured.portfolioId)
            assertEquals("GOOG", slot.captured.ticker)
            assertEquals("USD", slot.captured.currency)
            assertEquals(BigDecimal("0.15"), slot.captured.weightPct)
            assertEquals("Tech", slot.captured.sector)
        }
    }

    @Nested
    inner class DeletePosition {
        private val positionId = UUID.randomUUID()
        private val position = Position(
            id = positionId,
            portfolioId = portfolioId,
            ticker = "GOOG",
            currency = "USD",
            weightPct = BigDecimal("0.10"),
        )

        @Test
        fun `deletes position when ownership chain is valid`() {
            every { portfolioRepository.findById(portfolioId) } returns Optional.of(portfolio)
            every { positionRepository.findById(positionId) } returns Optional.of(position)
            every { positionRepository.deleteById(positionId) } returns Unit

            service.deletePosition(positionId, portfolioId, userId)

            verify { positionRepository.deleteById(positionId) }
        }

        @Test
        fun `throws when position belongs to different portfolio`() {
            val wrongPortfolioPosition = position.copy(portfolioId = UUID.randomUUID())
            every { portfolioRepository.findById(portfolioId) } returns Optional.of(portfolio)
            every { positionRepository.findById(positionId) } returns Optional.of(wrongPortfolioPosition)

            assertThrows<PortfolioNotFoundException> {
                service.deletePosition(positionId, portfolioId, userId)
            }
        }
    }

    @Nested
    inner class DisplayName {
        // Authoritative name lives in `instruments.name` for equities;
        // for cash it's synthesized from the currency. Regression guard for
        // the dropped `positions.name` column — a position row carrying
        // ticker "BE" must resolve to Bloom Energy's name via instruments,
        // never whatever stale string was stored on the row.
        @Test
        fun `equity name comes from instruments`() {
            val pos = Position(
                id = UUID.randomUUID(),
                portfolioId = portfolioId,
                ticker = "BE",
                currency = "USD",
                weightPct = BigDecimal("0.10"),
            )
            every { instrumentRepository.findById("BE") } returns Optional.of(
                Instrument(ticker = "BE", name = "Bloom Energy Corporation", currency = "USD")
            )

            assertEquals("Bloom Energy Corporation", service.displayNameFor(pos))
        }

        @Test
        fun `equity falls back to ticker when instruments row is missing`() {
            val pos = Position(
                portfolioId = portfolioId,
                ticker = "NEW1",
                currency = "USD",
                weightPct = BigDecimal("0.05"),
            )
            every { instrumentRepository.findById("NEW1") } returns Optional.empty()

            assertEquals("NEW1", service.displayNameFor(pos))
        }

        @Test
        fun `cash name is synthesized from currency regardless of instruments`() {
            val pos = Position(
                portfolioId = portfolioId,
                positionType = "CASH",
                ticker = "CASH.EUR",
                currency = "EUR",
                weightPct = BigDecimal("0.05"),
            )

            assertEquals("EUR Cash", service.displayNameFor(pos))
            verify(exactly = 0) { instrumentRepository.findById(any()) }
        }

        @Test
        fun `displayNames batches instruments lookup and keys by position id`() {
            val be = Position(
                id = UUID.randomUUID(), portfolioId = portfolioId,
                ticker = "BE", currency = "USD", weightPct = BigDecimal("0.10"),
            )
            val goog = Position(
                id = UUID.randomUUID(), portfolioId = portfolioId,
                ticker = "GOOG", currency = "USD", weightPct = BigDecimal("0.20"),
            )
            val cash = Position(
                id = UUID.randomUUID(), portfolioId = portfolioId,
                positionType = "CASH", ticker = "CASH.USD",
                currency = "USD", weightPct = BigDecimal("0.05"),
            )
            every { instrumentRepository.findByTickers(match { it.toSet() == setOf("BE", "GOOG") }) } returns listOf(
                Instrument(ticker = "BE", name = "Bloom Energy Corporation", currency = "USD"),
                Instrument(ticker = "GOOG", name = "Alphabet Inc.", currency = "USD"),
            )

            val names = service.displayNames(listOf(be, goog, cash))

            assertEquals("Bloom Energy Corporation", names[be.id])
            assertEquals("Alphabet Inc.", names[goog.id])
            assertEquals("USD Cash", names[cash.id])
        }
    }
}
