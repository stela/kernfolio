package com.kernfolio.controller

import com.kernfolio.repository.OptimizationRunRepository
import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.OptimizationException
import com.kernfolio.service.OptimizerService
import com.kernfolio.service.PortfolioService
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

data class OptimizeForm(
    val algorithm: String = "black_litterman",
    val covarianceMethod: String = "ledoit_wolf",
    val riskFreeRate: Double = 0.03,
    val tau: Double = 0.05,
    val kellyFraction: Double = 0.5,
    val minWeight: Double = 0.0,
    val maxWeight: Double = 0.40,
    val longOnly: Boolean = true,
)

@Controller
class OptimizationController(
    private val optimizerService: OptimizerService,
    private val portfolioService: PortfolioService,
    private val optimizationRunRepository: OptimizationRunRepository,
) {

    @GetMapping("/portfolios/{id}/optimize")
    fun optimizeForm(
        @PathVariable id: UUID,
        model: Model,
        authentication: Authentication,
    ): String {
        val portfolio = portfolioService.findByIdAndUserId(id, currentUserId(authentication))
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        model.addAttribute("portfolio", portfolio)
        model.addAttribute("optimizeForm", OptimizeForm())
        return "optimize"
    }

    @PostMapping("/portfolios/{id}/optimize")
    fun runOptimization(
        @PathVariable id: UUID,
        @ModelAttribute optimizeForm: OptimizeForm,
        model: Model,
        authentication: Authentication,
    ): String {
        val userId = currentUserId(authentication)
        portfolioService.findByIdAndUserId(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        return try {
            val result = optimizerService.optimize(
                portfolioId = id,
                userId = userId,
                algorithm = optimizeForm.algorithm,
                covarianceMethod = optimizeForm.covarianceMethod,
                riskFreeRate = optimizeForm.riskFreeRate,
                tau = optimizeForm.tau,
                kellyFraction = optimizeForm.kellyFraction,
                minWeight = optimizeForm.minWeight,
                maxWeight = optimizeForm.maxWeight,
                longOnly = optimizeForm.longOnly,
            )
            "redirect:/portfolios/$id/results/${result.id}"
        } catch (e: OptimizationException) {
            model.addAttribute("error", e.message)
            "fragments/optimize-results :: error"
        }
    }

    @GetMapping("/portfolios/{id}/results/{runId}")
    fun results(
        @PathVariable id: UUID,
        @PathVariable runId: UUID,
        model: Model,
        authentication: Authentication,
    ): String {
        val userId = currentUserId(authentication)
        val portfolio = portfolioService.findByIdAndUserId(id, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        val run = optimizationRunRepository.findById(runId).orElse(null)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND)
        if (run.portfolioId != id) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND)
        }
        val positions = portfolioService.findPositionsByPortfolioId(id, userId)
        model.addAttribute("portfolio", portfolio)
        model.addAttribute("run", run)
        model.addAttribute("positions", positions)
        return "results"
    }

    private fun currentUserId(authentication: Authentication): UUID =
        (authentication.principal as KernfolioUserDetails).id
}
