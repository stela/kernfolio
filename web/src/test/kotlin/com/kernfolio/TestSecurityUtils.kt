package com.kernfolio

import com.kernfolio.domain.User
import com.kernfolio.security.KernfolioUserDetails
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
import java.util.UUID

fun mockUserDetails(user: User) = SecurityMockMvcRequestPostProcessors.user(KernfolioUserDetails(user))

fun mockUserDetails(id: UUID, username: String, role: String = "USER") =
    mockUserDetails(User(
        id = id,
        username = username,
        email = "$username@test.com",
        passwordHash = "unused",
        role = role,
    ))
