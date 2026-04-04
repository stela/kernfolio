package com.kernfolio.controller

import com.kernfolio.security.KernfolioUserDetails
import com.kernfolio.service.FeatureFlagService
import com.kernfolio.service.InviteCodeService
import com.kernfolio.service.UserService
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
) {

    @GetMapping
    fun dashboard(model: Model): String {
        model.addAttribute("userCount", userService.countUsers())
        model.addAttribute("enabledUserCount", userService.countEnabledUsers())
        model.addAttribute("flagCount", featureFlagService.findAll().size)
        model.addAttribute("activeFlagCount", featureFlagService.findAll().count { it.enabled })
        return "admin/dashboard"
    }

    @GetMapping("/users")
    fun users(model: Model): String {
        model.addAttribute("users", userService.findAll())
        model.addAttribute("inviteCodes", inviteCodeService.findAllValid())
        return "admin/users"
    }

    @PostMapping("/users/invite")
    @Transactional
    fun generateInviteCode(authentication: Authentication, redirectAttributes: RedirectAttributes): String {
        val adminId = (authentication.principal as KernfolioUserDetails).id
        val invite = inviteCodeService.generateCode(adminId)
        redirectAttributes.addFlashAttribute("generatedCode", invite.code)
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
        return "fragments/admin-user-row :: user-row"
    }

    @GetMapping("/flags")
    fun flags(model: Model): String {
        model.addAttribute("flags", featureFlagService.findAll())
        return "admin/flags"
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
        return "fragments/admin-flag-row :: flag-row"
    }
}
