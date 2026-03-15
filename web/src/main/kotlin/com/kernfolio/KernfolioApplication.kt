package com.kernfolio

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class KernfolioApplication

fun main(args: Array<String>) {
    runApplication<KernfolioApplication>(*args)
}
