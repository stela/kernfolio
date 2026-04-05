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
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

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
            val cert = loadCertificate(File(certPath))
            val key = loadPrivateKey(File(keyPath))

            val sslContext = SslContextBuilder.forClient()
                .trustManager(caFile)
                .keyManager(key, cert)
                .build()

            val httpClient = HttpClient.create()
                .secure { it.sslContext(sslContext) }

            builder.clientConnector(ReactorClientHttpConnector(httpClient))
        } else {
            log.info("No mTLS certs found, using plain HTTP for optimizer WebClient")
        }

        return builder.build()
    }

    private fun loadCertificate(file: File): X509Certificate =
        file.inputStream().use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }

    private fun loadPrivateKey(file: File): PrivateKey {
        val pem = file.readText()
            .replace(Regex("-----BEGIN [A-Z ]+ KEY-----"), "")
            .replace(Regex("-----END [A-Z ]+ KEY-----"), "")
            .replace(Regex("\\s"), "")
        val der = Base64.getDecoder().decode(pem)
        val spec = PKCS8EncodedKeySpec(der)

        // Try Ed25519 first, then EC, then RSA
        for (algorithm in listOf("Ed25519", "EC", "RSA")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec)
            } catch (_: Exception) { }
        }
        throw IllegalArgumentException("Unable to load private key: unsupported algorithm")
    }
}
