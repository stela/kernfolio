package com.kernfolio.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.cert.X509Certificate
import javax.naming.ldap.LdapName

/**
 * Narrows `server.ssl.client-auth: need` from "any cert issued by the Vault
 * CA" (which includes the optimizer's and postgres's) to the reverse proxy
 * alone. Not Spring Security's x509() on purpose: that would authenticate
 * every request as a pre-authenticated "caddy" principal and bypass form login.
 */
@Component
@Profile("tls")
@Order(Ordered.HIGHEST_PRECEDENCE)
class ClientCertCnFilter(
    @Value("\${kernfolio.tls.allowed-client-cns}") private val allowedCns: Set<String>,
) : OncePerRequestFilter() {

    private val log = LoggerFactory.getLogger(ClientCertCnFilter::class.java)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val cn = clientCn(request)
        if (cn == null || cn !in allowedCns) {
            log.warn("Rejecting request from {}: client certificate CN {} is not allowed", request.remoteAddr, cn)
            // Not sendError(): its ERROR dispatch to /error runs the security
            // chain as anonymous and comes back as a 302 to /login.
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun clientCn(request: HttpServletRequest): String? {
        val chain = request.getAttribute("jakarta.servlet.request.X509Certificate") as? Array<*>
        val leaf = chain?.firstOrNull() as? X509Certificate ?: return null
        return LdapName(leaf.subjectX500Principal.name).rdns
            .firstOrNull { it.type.equals("CN", ignoreCase = true) }
            ?.value?.toString()
    }
}
