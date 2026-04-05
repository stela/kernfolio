package com.kernfolio.config

import com.kernfolio.security.CspNonceFilter
import com.kernfolio.security.TenantFilter
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.core.AuthenticationException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.HeaderWriterFilter

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain = http
        .authorizeHttpRequests { auth ->
            auth.requestMatchers("/", "/login", "/register", "/about", "/static/**", "/webjars/**").permitAll()
            auth.requestMatchers("/admin/**").hasRole("ADMIN")
            auth.requestMatchers("/api/**").authenticated()
            auth.anyRequest().authenticated()
        }
        .formLogin { form ->
            form.loginPage("/login").defaultSuccessUrl("/", true)
        }
        .logout { logout ->
            logout.logoutSuccessUrl("/login?logout")
        }
        .exceptionHandling { exceptions ->
            exceptions.authenticationEntryPoint(ApiAwareAuthenticationEntryPoint())
        }
        .headers { headers ->
            headers.referrerPolicy { it.policy(org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN) }
            headers.permissionsPolicy { it.policy("camera=(), microphone=(), geolocation=()") }
        }
        .addFilterAfter(CspNonceFilter(), HeaderWriterFilter::class.java)
        .addFilterAfter(TenantFilter(), UsernamePasswordAuthenticationFilter::class.java)
        .build()
}

private class ApiAwareAuthenticationEntryPoint : AuthenticationEntryPoint {

    private val loginEntryPoint = LoginUrlAuthenticationEntryPoint("/login")

    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        if (request.requestURI.startsWith("/api/")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED)
        } else {
            loginEntryPoint.commence(request, response, authException)
        }
    }
}
