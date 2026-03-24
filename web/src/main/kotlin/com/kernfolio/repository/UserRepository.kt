package com.kernfolio.repository

import com.kernfolio.domain.User
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface UserRepository : ListCrudRepository<User, UUID> {
    fun findByUsername(username: String): User?
    fun findByEmail(email: String): User?
}
