package com.kernfolio.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter
import java.security.SecureRandom
import java.util.Base64

class CspNonceFilter : OncePerRequestFilter() {

    private val random = SecureRandom()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val nonceBytes = ByteArray(16)
        random.nextBytes(nonceBytes)
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)

        request.setAttribute("cspNonce", nonce)

        response.setHeader(
            "Content-Security-Policy",
            "default-src 'self'; " +
                "script-src 'self' 'nonce-$nonce'; " +
                "style-src 'self' 'nonce-$nonce'; " +
                "img-src 'self' data:; " +
                "font-src 'self'; " +
                "connect-src 'self'; " +
                "frame-ancestors 'none'; " +
                "form-action 'self'",
        )

        filterChain.doFilter(request, response)
    }
}
