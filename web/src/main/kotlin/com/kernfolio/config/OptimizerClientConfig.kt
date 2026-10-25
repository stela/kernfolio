package com.kernfolio.config

import com.kernfolio.config.tls.ReloadingPemKeyManager
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.util.KeyManagerFactoryWrapper
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
import java.nio.file.Path

@Configuration
class OptimizerClientConfig {

    private val log = LoggerFactory.getLogger(OptimizerClientConfig::class.java)

    @Bean
    fun optimizerWebClient(
        @Value("\${optimizer.base-url}") baseUrl: String,
        @Value("\${openbao.certs.ca:}") caPath: String,
        @Value("\${openbao.certs.cert:}") certPath: String,
        @Value("\${openbao.certs.key:}") keyPath: String,
    ): WebClient {
        val builder = WebClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .codecs { it.defaultCodecs().maxInMemorySize(4 * 1024 * 1024) }

        if (caPath.isNotBlank() && File(caPath).exists()) {
            log.info("Configuring mTLS for optimizer WebClient")
            // The JDK provider (there's no netty-tcnative anyway) consults this
            // key manager on every handshake, so a renewed app-client.pem is
            // presented without a restart.
            val keyManager = ReloadingPemKeyManager(Path.of(certPath), Path.of(keyPath))
            val sslContext = SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(File(caPath))
                .keyManager(KeyManagerFactoryWrapper(keyManager))
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
