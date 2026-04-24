package com.kernfolio.repository

import com.kernfolio.domain.Instrument
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository
import org.springframework.data.repository.query.Param

interface InstrumentRepository : ListCrudRepository<Instrument, String> {

    @Query("SELECT * FROM instruments WHERE ticker IN (:tickers)")
    fun findByTickers(tickers: Collection<String>): List<Instrument>

    // Word-prefix match: query must prefix the ticker OR prefix one of the
    // words in the name. Unanchored substring matching on name (the prior
    // behaviour) was surprising — e.g. typing "be" would surface "Alphabet
    // Inc." because "be" sits in the middle of "Alpha-be-t". Word-prefix
    // keeps natural typeahead ("hath" → Berkshire Hathaway) while rejecting
    // mid-word coincidences.
    @Query(
        """
        SELECT * FROM instruments
        WHERE LOWER(ticker) LIKE LOWER(:query) || '%'
           OR LOWER(name)   LIKE LOWER(:query) || '%'
           OR LOWER(name)   LIKE '% ' || LOWER(:query) || '%'
        ORDER BY
            CASE
                WHEN LOWER(ticker) = LOWER(:query) THEN 0
                WHEN LOWER(ticker) LIKE LOWER(:query) || '%' THEN 1
                WHEN LOWER(name)   LIKE LOWER(:query) || '%' THEN 2
                ELSE 3
            END,
            market_cap_usd DESC NULLS LAST,
            ticker
        LIMIT :limit
        """,
    )
    fun search(@Param("query") query: String, @Param("limit") limit: Int): List<Instrument>
}
