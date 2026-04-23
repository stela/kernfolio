package com.kernfolio.controller

import com.kernfolio.service.FeatureFlagService
import com.kernfolio.service.InviteCodeService
import com.kernfolio.service.InviteCodeValidationResult
import com.kernfolio.service.UserService
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.ui.Model
import org.springframework.validation.BindingResult
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes

data class RegistrationForm(
    @field:NotBlank(message = "Username is required")
    @field:Size(min = 3, max = 50, message = "Username must be between 3 and 50 characters")
    val username: String = "",

    @field:NotBlank(message = "Email is required")
    @field:Email(message = "Please provide a valid email address")
    val email: String = "",

    @field:NotBlank(message = "Password is required")
    @field:Size(min = 8, max = 100, message = "Password must be at least 8 characters")
    val password: String = "",

    @field:NotBlank(message = "Please confirm your password")
    val confirmPassword: String = "",

    val inviteCode: String = "",
)

@Controller
class AuthController(
    private val userService: UserService,
    private val inviteCodeService: InviteCodeService,
    private val featureFlagService: FeatureFlagService,
) {

    @GetMapping("/login")
    fun loginPage(
        @RequestParam(required = false) error: String?,
        @RequestParam(required = false) logout: String?,
        @RequestParam(required = false) registered: String?,
        @RequestParam(required = false) sessionExpired: String?,
        model: Model,
    ): String {
        model.addAttribute("error", error != null)
        model.addAttribute("logout", logout != null)
        model.addAttribute("registered", registered != null)
        model.addAttribute("sessionExpired", sessionExpired != null)
        return "page/login"
    }

    @GetMapping("/register")
    fun registerPage(
        @RequestParam(required = false) sessionExpired: String?,
        model: Model,
    ): String {
        model.addAttribute("registrationForm", RegistrationForm())
        model.addAttribute("selfRegistrationEnabled", featureFlagService.isGloballyEnabled("SELF_REGISTRATION"))
        model.addAttribute("sessionExpired", sessionExpired != null)
        return "page/register"
    }

    @PostMapping("/register")
    @Transactional
    fun register(
        @Valid @ModelAttribute registrationForm: RegistrationForm,
        bindingResult: BindingResult,
        model: Model,
        redirectAttributes: RedirectAttributes,
    ): String {
        val selfRegistrationEnabled = featureFlagService.isGloballyEnabled("SELF_REGISTRATION")
        model.addAttribute("selfRegistrationEnabled", selfRegistrationEnabled)

        if (registrationForm.password != registrationForm.confirmPassword) {
            bindingResult.rejectValue("confirmPassword", "password.mismatch", "Passwords do not match")
        }

        // Generic error to prevent username/email enumeration
        if (userService.existsByUsername(registrationForm.username) ||
            userService.existsByEmail(registrationForm.email)
        ) {
            bindingResult.reject("registration.failed", "Registration could not be completed. Please check your details and try again.")
        }

        if (!selfRegistrationEnabled) {
            if (registrationForm.inviteCode.isBlank()) {
                bindingResult.rejectValue("inviteCode", "invite.required", "Invite code is required")
            } else {
                when (inviteCodeService.validateCode(registrationForm.inviteCode)) {
                    is InviteCodeValidationResult.Valid -> { /* ok */ }
                    is InviteCodeValidationResult.NotFound,
                    is InviteCodeValidationResult.AlreadyUsed,
                    is InviteCodeValidationResult.Expired -> {
                        bindingResult.rejectValue("inviteCode", "invite.invalid", "Invalid or expired invite code")
                    }
                }
            }
        }

        if (bindingResult.hasErrors()) {
            model.addAttribute("errors", FormErrors(bindingResult))
            return "page/register"
        }

        val role = if (userService.countEnabledUsers() == 0L) "ADMIN" else "USER"
        val user = userService.createUser(
            username = registrationForm.username,
            email = registrationForm.email,
            rawPassword = registrationForm.password,
            role = role,
        )

        if (!selfRegistrationEnabled && registrationForm.inviteCode.isNotBlank()) {
            inviteCodeService.redeemCode(registrationForm.inviteCode, user.id!!)
        }

        return "redirect:/login?registered"
    }
}
