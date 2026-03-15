package com.kernfolio.twin.yfinance

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class YFinanceTwinApp

fun main(args: Array<String>) {
    runApplication<YFinanceTwinApp>(*args)
}
