package com.kernfolio.repository

import com.kernfolio.domain.CachedPrice
import org.springframework.data.jdbc.repository.query.Modifying
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.Repository
import java.math.BigDecimal
import java.time.LocalDate

interface CachedPriceRepository : Repository<CachedPrice, String> {

    @Query("SELECT * FROM cached_prices WHERE ticker = :ticker AND price_date BETWEEN :startDate AND :endDate ORDER BY price_date")
    fun findByTickerAndDateRange(ticker: String, startDate: LocalDate, endDate: LocalDate): List<CachedPrice>

    @Query("SELECT * FROM cached_prices WHERE ticker IN (:tickers) AND price_date BETWEEN :startDate AND :endDate ORDER BY ticker, price_date")
    fun findByTickersAndDateRange(tickers: Collection<String>, startDate: LocalDate, endDate: LocalDate): List<CachedPrice>

    @Query("SELECT * FROM cached_prices WHERE ticker = :ticker ORDER BY price_date DESC LIMIT 1")
    fun findLatestByTicker(ticker: String): CachedPrice?

    @Modifying
    @Query("""
        INSERT INTO cached_prices (ticker, price_date, close_price, currency, fetched_at)
        VALUES (:ticker, :priceDate, :closePrice, :currency, now())
        ON CONFLICT (ticker, price_date) DO UPDATE SET close_price = :closePrice, currency = :currency, fetched_at = now()
    """)
    fun upsert(ticker: String, priceDate: LocalDate, closePrice: BigDecimal, currency: String)

    @Query("SELECT * FROM cached_prices")
    fun findAll(): List<CachedPrice>

    @Query("SELECT MAX(price_date) FROM cached_prices WHERE ticker = :ticker")
    fun findMaxDateByTicker(ticker: String): LocalDate?
}
