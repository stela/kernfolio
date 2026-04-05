package com.kernfolio.config

import io.netty.handler.ssl.SslContextBuilder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import reactor.netty.http.client.HttpClient
import java.io.File

@Configuration
class OptimizerClientConfig {

    private val log = LoggerFactory.getLogger(OptimizerClientConfig::class.java)

    @Bean
    fun optimizerWebClient(
        @Value("\${optimizer.base-url}") baseUrl: String,
        @Value("\${vault.certs.ca:}") caPath: String,
        @Value("\${vault.certs.cert:}") certPath: String,
        @Value("\${vault.certs.key:}") keyPath: String,
    ): WebClient {
        val builder = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }

        if (caPath.isNotBlank() && File(caPath).exists()) {
            log.info("Configuring mTLS for optimizer WebClient")
            val caFile = File(caPath)
            val certFile = File(certPath)
            val keyFile = File(keyPath)

            val sslContext = SslContextBuilder.forClient()
                .trustManager(caFile)
                .keyManager(certFile, keyFile)
                .build()

            val httpClient = HttpClient.create()
                .secure { it.sslContext(sslContext) }

            builder.clientConnector(ReactorClientHttpConnector(httpClient))
        } else {
            log.info("No mTLS certs found, using plain HTTP for optimizer WebClient")
        }

        return builder.build()
    }
}
