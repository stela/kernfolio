package com.kernfolio.domain

import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Transient
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

@Table("instruments")
class Instrument(
    @Id val ticker: String,
    val name: String? = null,
    val exchange: String? = null,
    val currency: String? = null,
    val sector: String? = null,
    val marketCapUsd: BigDecimal? = null,
    val lastRefreshedAt: Instant? = null,
) : Persistable<String> {

    @Transient
    private var newFlag: Boolean = true

    override fun getId(): String = ticker
    override fun isNew(): Boolean = newFlag

    fun markNotNew(): Instrument = this.also { newFlag = false }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Instrument) return false
        return ticker == other.ticker
    }

    override fun hashCode(): Int = ticker.hashCode()

    override fun toString(): String =
        "Instrument(ticker=$ticker, name=$name, exchange=$exchange, currency=$currency, sector=$sector)"
}
