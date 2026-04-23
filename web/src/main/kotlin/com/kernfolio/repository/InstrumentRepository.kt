package com.kernfolio.repository

import com.kernfolio.domain.Instrument
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.query.Param

interface InstrumentRepository : ListCrudRepository<Instrument, String> {

    @Query("SELECT * FROM instruments WHERE ticker IN (:tickers)")
    fun findByTickers(tickers: Collection<String>): List<Instrument>

    @Query(
        """
        SELECT * FROM instruments
        WHERE LOWER(ticker) LIKE LOWER(:query) || '%'
           OR LOWER(name) LIKE '%' || LOWER(:query) || '%'
        ORDER BY
            CASE
                WHEN LOWER(ticker) = LOWER(:query) THEN 0
                WHEN LOWER(ticker) LIKE LOWER(:query) || '%' THEN 1
                WHEN LOWER(name) LIKE LOWER(:query) || '%' THEN 2
                ELSE 3
            END,
            market_cap_usd DESC NULLS LAST,
            ticker
        LIMIT :limit
        """,
    )
    fun search(@Param("query") query: String, @Param("limit") limit: Int): List<Instrument>
}
