package com.kernfolio.config

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ModelAttribute

@ControllerAdvice
class GlobalModelAdvice {

    @ModelAttribute("nonce")
    fun nonce(request: HttpServletRequest): String =
        request.getAttribute("cspNonce") as? String ?: ""

    @ModelAttribute("csrfToken")
    fun csrfToken(request: HttpServletRequest): String {
        val csrf = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken
            ?: request.getAttribute("_csrf") as? CsrfToken
        return csrf?.token ?: ""
    }

    @ModelAttribute("csrfHeaderName")
    fun csrfHeaderName(request: HttpServletRequest): String {
        val csrf = request.getAttribute(CsrfToken::class.java.name) as? CsrfToken
            ?: request.getAttribute("_csrf") as? CsrfToken
        return csrf?.headerName ?: "X-CSRF-TOKEN"
    }
}
