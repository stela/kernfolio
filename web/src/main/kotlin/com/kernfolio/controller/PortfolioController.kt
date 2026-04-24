package com.kernfolio.controller

import com.kernfolio.domain.Position
import com.kernfolio.repository.CachedPriceRepository
import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.OptimizerService
import com.kernfolio.service.PortfolioNotFoundException
import com.kernfolio.service.PortfolioService
import com.kernfolio.service.PositionForm
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.util.UUID

data class PortfolioForm(
    @field:NotBlank(message = "Portfolio name is required")
    @field:Size(max = 100, message = "Name must be 100 characters or less")
    val name: String = "",
    val description: String? = null,
    @field:Size(min = 3, max = 3, message = "Currency must be a 3-letter code")
    val baseCurrency: String = "EUR",
)

@Controller
class PortfolioController(
    private val portfolioService: PortfolioService,
    private val cachedPriceRepository: CachedPriceRepository,
) {
    // 5-year CAGR from the latest cached price vs the stored intrinsic
    // value — mirrors OptimizerService.computeCagr. Returns null for
    // CASH positions, missing IV, or when we have no price yet.
    private fun cagrFor(position: Position): Double? {
        if (position.positionType != "EQUITY") return null
        val iv = position.intrinsicValueLocal ?: return null
        val latest = cachedPriceRepository.findLatestByTicker(position.ticker) ?: return null
        val price = latest.closePrice
        if (price.compareTo(BigDecimal.ZERO) == 0) return null
        return OptimizerService.computeCagr(iv.toDouble(), price.toDouble())
    }

    private fun cagrMap(positions: List<Position>): Map<UUID, Double> =
        positions.mapNotNull { pos ->
            val id = pos.id ?: return@mapNotNull null
            cagrFor(pos)?.let { id to it }
        }.toMap()

    @GetMapping("/portfolios/new")
    fun newPortfolio(model: Model): String {
        model.addAttribute("portfolioForm", PortfolioForm())
        return "page/portfolio-form"
    }

    @PostMapping("/portfolios")
    @Transactional
    fun createPortfolio(
        @Valid @ModelAttribute portfolioForm: PortfolioForm,
        bindingResult: BindingResult,
        model: Model,
        authentication: Authentication,
    ): String {
        if (bindingResult.hasErrors()) {
            model.addAttribute("errors", FormErrors(bindingResult))
            return "page/portfolio-form"
        }
        val portfolio = portfolioService.create(
            userId = currentUserId(authentication),
            name = portfolioForm.name,
            description = portfolioForm.description,
            baseCurrency = portfolioForm.baseCurrency,
        )
        return "redirect:/portfolios/${portfolio.id}"
    }

    @GetMapping("/portfolios/{id}")
    fun portfolioDetail(@PathVariable id: UUID, model: Model, authentication: Authentication): String {
        val userId = currentUserId(authentication)
        val portfolio = portfolioService.findByIdAndUserId(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val positions = portfolioService.findPositionsByPortfolioId(id, userId)
        model.addAttribute("portfolio", portfolio)
        model.addAttribute("positions", positions)
        model.addAttribute("displayNames", portfolioService.displayNames(positions))
        model.addAttribute("cagrByPositionId", cagrMap(positions))
        model.addAttribute("positionForm", PositionForm())
        return "page/portfolio-detail"
    }

    @GetMapping("/portfolios/{id}/edit")
    fun editPortfolio(@PathVariable id: UUID, model: Model, authentication: Authentication): String {
        val portfolio = portfolioService.findByIdAndUserId(id, currentUserId(authentication))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        model.addAttribute("portfolioForm", PortfolioForm(
            name = portfolio.name,
            description = portfolio.description,
            baseCurrency = portfolio.baseCurrency,
        ))
        model.addAttribute("portfolioId", portfolio.id)
        return "page/portfolio-form"
    }

    @PostMapping("/portfolios/{id}")
    @Transactional
    fun updatePortfolio(
        @PathVariable id: UUID,
        @Valid @ModelAttribute portfolioForm: PortfolioForm,
        bindingResult: BindingResult,
        model: Model,
        authentication: Authentication,
    ): String {
        if (bindingResult.hasErrors()) {
            model.addAttribute("portfolioId", id)
            model.addAttribute("errors", FormErrors(bindingResult))
            return "page/portfolio-form"
        }
        try {
            portfolioService.update(
                id = id,
                userId = currentUserId(authentication),
                name = portfolioForm.name,
                description = portfolioForm.description,
                baseCurrency = portfolioForm.baseCurrency,
            )
        } catch (_: PortfolioNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return "redirect:/portfolios/$id"
    }

    @PostMapping("/portfolios/{id}/delete")
    @Transactional
    fun deletePortfolio(@PathVariable id: UUID, authentication: Authentication): String {
        try {
            portfolioService.delete(id, currentUserId(authentication))
        } catch (_: PortfolioNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return "redirect:/dashboard"
    }

    // -- Position endpoints (partial templates) --

    @GetMapping("/portfolios/{id}/positions/new-row")
    fun newPositionRow(@PathVariable id: UUID, model: Model, authentication: Authentication): String {
        portfolioService.findByIdAndUserId(id, currentUserId(authentication))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        model.addAttribute("portfolioId", id)
        model.addAttribute("positionForm", PositionForm())
        return "partial/position-new-row"
    }

    @PostMapping("/portfolios/{id}/positions")
    @Transactional
    fun addPosition(
        @PathVariable id: UUID,
        @ModelAttribute positionForm: PositionForm,
        authentication: Authentication,
        model: Model,
    ): String {
        try {
            val position = portfolioService.addPosition(id, currentUserId(authentication), positionForm)
            model.addAttribute("position", position)
            model.addAttribute("displayName", portfolioService.displayNameFor(position))
            model.addAttribute("portfolioId", id)
            model.addAttribute("cagr", cagrFor(position))
            return "partial/position-saved-row"
        } catch (_: PortfolioNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @GetMapping("/portfolios/{id}/positions/{posId}/edit-row")
    fun editPositionRow(
        @PathVariable id: UUID,
        @PathVariable posId: UUID,
        authentication: Authentication,
        model: Model,
    ): String {
        val position = try {
            portfolioService.findPosition(posId, id, currentUserId(authentication))
        } catch (_: PortfolioNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        model.addAttribute("position", position)
        model.addAttribute("displayName", portfolioService.displayNameFor(position))
        model.addAttribute("portfolioId", id)
        return "partial/position-edit-row"
    }

    @PutMapping("/portfolios/{id}/positions/{posId}")
    @Transactional
    fun updatePosition(
        @PathVariable id: UUID,
        @PathVariable posId: UUID,
        @ModelAttribute positionForm: PositionForm,
        authentication: Authentication,
        model: Model,
    ): String {
        try {
            val position = portfolioService.updatePosition(posId, id, currentUserId(authentication), positionForm)
            model.addAttribute("position", position)
            model.addAttribute("displayName", portfolioService.displayNameFor(position))
            model.addAttribute("portfolioId", id)
            model.addAttribute("cagr", cagrFor(position))
            return "partial/position-saved-row"
        } catch (_: PortfolioNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
    }

    @DeleteMapping("/portfolios/{id}/positions/{posId}")
    @Transactional
    @ResponseBody
    fun deletePosition(
        @PathVariable id: UUID,
        @PathVariable posId: UUID,
        authentication: Authentication,
    ): String {
        try {
            portfolioService.deletePosition(posId, id, currentUserId(authentication))
        } catch (_: PortfolioNotFoundException) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        return ""
    }

    private fun currentUserId(authentication: Authentication): UUID =
        (authentication.principal as KernfolioUserDetails).id
}
