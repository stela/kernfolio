package com.kernfolio.security

import com.kernfolio.domain.User
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails
import java.util.UUID

class KernfolioUserDetails(private val user: User) : UserDetails {

    val id: UUID get() = user.id!!

    val role: String get() = user.role

    override fun getAuthorities(): Collection<GrantedAuthority> =
        listOf(SimpleGrantedAuthority("ROLE_${user.role}"))

    override fun getPassword(): String = user.passwordHash

    override fun getUsername(): String = user.username

    override fun isEnabled(): Boolean = user.enabled

    override fun isAccountNonExpired(): Boolean = true

    override fun isAccountNonLocked(): Boolean = true

    override fun isCredentialsNonExpired(): Boolean = true
}
