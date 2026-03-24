package com.kernfolio.security

import java.util.UUID

data class TenantInfo(val userId: UUID, val role: String)

object TenantContext {

    private val holder = ThreadLocal<TenantInfo?>()

    fun set(info: TenantInfo) {
        holder.set(info)
    }

    fun get(): TenantInfo? = holder.get()

    fun clear() {
        holder.remove()
    }
}
