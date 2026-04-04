package com.kernfolio.config

import com.kernfolio.service.InviteCodeService
import com.kernfolio.service.UserService
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
@ConditionalOnProperty(name = ["spring.liquibase.enabled"], havingValue = "true", matchIfMissing = true)
class AdminBootstrapRunner(
    private val userService: UserService,
    private val inviteCodeService: InviteCodeService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(AdminBootstrapRunner::class.java)

    @Transactional
    override fun run(args: ApplicationArguments) {
        if (userService.countUsers() > 0) return

        val systemUser = userService.createUser(
            username = "__system__",
            email = "system@kernfolio.local",
            rawPassword = java.util.UUID.randomUUID().toString(),
            role = "ADMIN",
        )

        val inviteCode = inviteCodeService.generateCode(createdBy = systemUser.id!!)

        log.warn("No users found. Admin invite code: {}. Register at /register to create the first admin account.", inviteCode.code)
    }
}
