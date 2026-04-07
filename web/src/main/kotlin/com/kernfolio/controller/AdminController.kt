package com.kernfolio.controller

import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.EmailService
import com.kernfolio.service.FeatureFlagService
import com.kernfolio.service.InviteCodeService
import com.kernfolio.service.UserService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import java.util.UUID

@Controller
@RequestMapping("/admin")
class AdminController(
    private val userService: UserService,
    private val inviteCodeService: InviteCodeService,
    private val featureFlagService: FeatureFlagService,
    private val emailService: EmailService? = null,
) {

    @GetMapping
    fun dashboard(model: Model): String {
        model.addAttribute("userCount", userService.countUsers())
        model.addAttribute("enabledUserCount", userService.countEnabledUsers())
        model.addAttribute("flagCount", featureFlagService.findAll().size)
        model.addAttribute("activeFlagCount", featureFlagService.findAll().count { it.enabled })
        return "page/admin/dashboard"
    }

    @GetMapping("/users")
    fun users(model: Model): String {
        model.addAttribute("users", userService.findAll())
        model.addAttribute("inviteCodes", inviteCodeService.findAllValid())
        return "page/admin/users"
    }

    @PostMapping("/users/invite")
    @Transactional
    fun generateInviteCode(
        @RequestParam(required = false) email: String?,
        authentication: Authentication,
        redirectAttributes: RedirectAttributes,
        request: HttpServletRequest,
    ): String {
        val adminId = (authentication.principal as KernfolioUserDetails).id
        val invite = inviteCodeService.generateCode(adminId)
        redirectAttributes.addFlashAttribute("generatedCode", invite.code)

        if (!email.isNullOrBlank() && emailService != null) {
            val baseUrl = request.requestURL.toString().substringBefore("/admin")
            emailService.sendInviteCode(email, invite.code, baseUrl)
            redirectAttributes.addFlashAttribute("emailSent", email)
        }

        return "redirect:/admin/users"
    }

    @PostMapping("/users/{id}/toggle")
    @Transactional
    fun toggleUser(
        @PathVariable id: UUID,
        @RequestParam enabled: Boolean,
        model: Model,
    ): String {
        val user = userService.setEnabled(id, enabled)
        model.addAttribute("user", user)
        return "partial/admin-user-row"
    }

    @GetMapping("/flags")
    fun flags(model: Model): String {
        model.addAttribute("flags", featureFlagService.findAll())
        return "page/admin/flags"
    }

    @PostMapping("/flags/{id}")
    @Transactional
    fun updateFlag(
        @PathVariable id: UUID,
        @RequestParam enabled: Boolean,
        @RequestParam rolloutPct: Int,
        model: Model,
    ): String {
        val flag = featureFlagService.updateFlag(id, enabled, rolloutPct)
        model.addAttribute("flag", flag)
        return "partial/admin-flag-row"
    }
}
