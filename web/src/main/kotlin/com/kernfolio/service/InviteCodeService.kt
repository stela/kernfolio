package com.kernfolio.service

import com.kernfolio.domain.InviteCode
import com.kernfolio.repository.InviteCodeRepository
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID

sealed class InviteCodeValidationResult {
    data class Valid(val inviteCode: InviteCode) : InviteCodeValidationResult()
    data object NotFound : InviteCodeValidationResult()
    data object AlreadyUsed : InviteCodeValidationResult()
    data object Expired : InviteCodeValidationResult()
}

@Service
class InviteCodeService(
    private val inviteCodeRepository: InviteCodeRepository,
    private val clock: Clock = Clock.systemUTC(),
) {

    private val random = SecureRandom()
    private val codeChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no ambiguous chars (0/O, 1/I)

    fun generateCode(createdBy: UUID, expiresAt: Instant? = null): InviteCode {
        val code = (1..8).map { codeChars[random.nextInt(codeChars.length)] }.joinToString("")
        return inviteCodeRepository.save(
            InviteCode(
                code = code,
                createdBy = createdBy,
                expiresAt = expiresAt,
            )
        )
    }

    fun validateCode(code: String): InviteCodeValidationResult {
        val inviteCode = inviteCodeRepository.findByCode(code)
            ?: return InviteCodeValidationResult.NotFound
        if (inviteCode.usedBy != null) return InviteCodeValidationResult.AlreadyUsed
        if (inviteCode.expiresAt != null && inviteCode.expiresAt.isBefore(clock.instant())) {
            return InviteCodeValidationResult.Expired
        }
        return InviteCodeValidationResult.Valid(inviteCode)
    }

    fun redeemCode(code: String, usedBy: UUID): InviteCode {
        val inviteCode = inviteCodeRepository.findByCode(code)
            ?: throw IllegalArgumentException("Invite code not found: $code")
        return inviteCodeRepository.save(
            inviteCode.copy(
                usedBy = usedBy,
                usedAt = clock.instant(),
            )
        )
    }
}
