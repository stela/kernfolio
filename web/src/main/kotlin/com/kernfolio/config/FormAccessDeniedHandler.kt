package com.kernfolio.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.csrf.CsrfException

// Redirects HTML form POSTs whose CSRF token has expired back to the GET
// view of the same URL with ?sessionExpired=1, so the failure surfaces as
// a banner on the form the user was submitting instead of a blank 403.
// Authorization failures (role checks, etc.) still get a plain 403 —
// they aren't session-expiry, and masking them as such would be misleading.
// AJAX/API requests always get a plain 403 so the Http wrapper can react.
class FormAccessDeniedHandler : AccessDeniedHandler {

    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        val isAjax = "XMLHttpRequest" == request.getHeader("X-Requested-With")
        val isApi = request.requestURI.startsWith("/api/")
        val isCsrf = accessDeniedException is CsrfException
        val isFormPost = "POST".equals(request.method, ignoreCase = true)

        if (!isAjax && !isApi && isCsrf && isFormPost) {
            val target = buildString {
                append(request.requestURI)
                val existing = request.queryString
                if (!existing.isNullOrBlank()) {
                    append('?').append(existing).append("&sessionExpired=1")
                } else {
                    append("?sessionExpired=1")
                }
            }
            response.sendRedirect(target)
            return
        }

        response.sendError(HttpServletResponse.SC_FORBIDDEN, accessDeniedException.message ?: "Forbidden")
    }
}
