package com.kernfolio.repository

import com.kernfolio.domain.Instrument
import org.springframework.data.jdbc.repository.query.Query
import org.springframework.data.repository.ListCrudRepository

interface InstrumentRepository : ListCrudRepository<Instrument, String> {

    @Query("SELECT * FROM instruments WHERE ticker IN (:tickers)")
    fun findByTickers(tickers: Collection<String>): List<Instrument>
}
