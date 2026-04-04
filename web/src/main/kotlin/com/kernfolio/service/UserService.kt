package com.kernfolio.service

import com.kernfolio.domain.User
import com.kernfolio.repository.UserRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class UserService(
    private val userRepository: UserRepository,
    private val passwordEncoder: PasswordEncoder,
) {

    fun createUser(username: String, email: String, rawPassword: String, role: String = "USER", enabled: Boolean = true): User =
        userRepository.save(
            User(
                username = username,
                email = email,
                passwordHash = passwordEncoder.encode(rawPassword)!!,
                role = role,
                enabled = enabled,
            )
        )

    fun existsByUsername(username: String): Boolean =
        userRepository.findByUsername(username) != null

    fun existsByEmail(email: String): Boolean =
        userRepository.findByEmail(email) != null

    fun countUsers(): Long =
        userRepository.count()

    fun countEnabledUsers(): Long =
        userRepository.findAll().count { it.enabled }.toLong()

    fun findAll(): List<User> =
        userRepository.findAll()

    fun findById(id: UUID): User? =
        userRepository.findById(id).orElse(null)

    fun setEnabled(id: UUID, enabled: Boolean): User {
        val user = userRepository.findById(id).orElseThrow { IllegalArgumentException("User not found: $id") }
        return userRepository.save(user.copy(enabled = enabled))
    }
}
