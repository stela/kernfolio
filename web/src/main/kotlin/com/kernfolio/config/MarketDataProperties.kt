package com.kernfolio.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "market-data")
data class MarketDataProperties(
    val defaultCurrencies: List<String> = emptyList(),
    val scheduler: SchedulerProperties = SchedulerProperties(),
) {
    data class SchedulerProperties(
        val cron: String = "0 0 22 * * *",
        val enabled: Boolean = true,
    )
}
