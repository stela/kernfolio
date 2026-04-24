package com.kernfolio.repository

import com.kernfolio.domain.CachedFxRate
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import java.math.BigDecimal
import java.time.LocalDate

interface CachedFxRateRepository : Repository<CachedFxRate, String> {

    @Query("SELECT * FROM cached_fx_rates WHERE currency_pair = :currencyPair AND rate_date BETWEEN :startDate AND :endDate ORDER BY rate_date")
    fun findByCurrencyPairAndDateRange(currencyPair: String, startDate: LocalDate, endDate: LocalDate): List<CachedFxRate>

    @Query("SELECT * FROM cached_fx_rates WHERE currency_pair = :currencyPair ORDER BY rate_date DESC LIMIT 1")
    fun findLatestByCurrencyPair(currencyPair: String): CachedFxRate?

    @Query("SELECT * FROM cached_fx_rates WHERE currency_pair = :currencyPair ORDER BY rate_date ASC LIMIT 1")
    fun findEarliestByCurrencyPair(currencyPair: String): CachedFxRate?

    @Modifying
    @Query("""
        INSERT INTO cached_fx_rates (currency_pair, rate_date, rate, fetched_at)
        VALUES (:currencyPair, :rateDate, :rate, now())
        ON CONFLICT (currency_pair, rate_date) DO UPDATE SET rate = :rate, fetched_at = now()
    """)
    fun upsert(currencyPair: String, rateDate: LocalDate, rate: BigDecimal)

    @Query("SELECT * FROM cached_fx_rates")
    fun findAll(): List<CachedFxRate>
}
