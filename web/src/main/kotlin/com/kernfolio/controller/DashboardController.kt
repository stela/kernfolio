package com.kernfolio.controller

import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.PortfolioService
import org.springframework.security.authentication.AnonymousAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping

@Controller
class DashboardController(
    private val portfolioService: PortfolioService,
) {

    @GetMapping("/")
    fun index(authentication: Authentication?): String {
        if (authentication == null || !authentication.isAuthenticated ||
            authentication is AnonymousAuthenticationToken
        ) {
            return "page/index"
        }
        return "redirect:/dashboard"
    }

    @GetMapping("/dashboard")
    fun dashboard(model: Model, authentication: Authentication): String {
        val user = authentication.principal as KernfolioUserDetails
        val portfolios = portfolioService.findByUserId(user.id)
        model.addAttribute("portfolios", portfolios)
        model.addAttribute("username", user.username)
        return "page/dashboard"
    }

    @GetMapping("/about")
    fun about(): String = "page/about"
}
