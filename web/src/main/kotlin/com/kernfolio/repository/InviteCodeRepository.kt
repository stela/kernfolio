package com.kernfolio.repository

import com.kernfolio.domain.InviteCode
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import java.util.UUID

interface InviteCodeRepository : ListCrudRepository<InviteCode, UUID> {
    fun findByCode(code: String): InviteCode?
    fun findByCreatedBy(createdBy: UUID): List<InviteCode>

    @Query("SELECT * FROM invite_codes WHERE used_by IS NULL AND (expires_at IS NULL OR expires_at > now())")
    fun findAllValid(): List<InviteCode>
}
