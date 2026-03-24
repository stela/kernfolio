package com.kernfolio.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

class TenantFilter : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        try {
            val authentication = SecurityContextHolder.getContext().authentication
            val principal = authentication?.principal
            if (principal is KernfolioUserDetails) {
                TenantContext.set(TenantInfo(userId = principal.id, role = principal.role))
            }
            filterChain.doFilter(request, response)
        } finally {
            TenantContext.clear()
        }
    }
}
