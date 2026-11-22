package com.kernfolio

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import jakarta.servlet.Filter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties
import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.boot.ssl.pem.PemContent
import java.io.IOException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Exercises the `tls` profile (application-tls.yml + ClientCertCnFilter) — the
 * Caddy -> web hop — which every other test skips by running plain HTTP.
 *
 * Certificates come from [TestPki] (a real OpenBao PKI set up like
 * openbao/init/setup.sh), so this also proves that Tomcat/JSSE and Spring's PEM
 * bundles accept what production is issued. They're copied into [certDir], so
 * the renewal test can replace app.pem without touching other tests' files.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class, WebMtlsIntegrationTest.RequestUrlProbe::class)
@ActiveProfiles("tls")
class WebMtlsIntegrationTest {

    /**
     * Answers [PROBE] with `request.requestURL`, which is what the invite email's
     * link is built from (AdminController). Runs after ClientCertCnFilter and just
     * before Spring Security, so it sees the request exactly as the app does.
     */
    @TestConfiguration(proxyBeanMethods = false)
    class RequestUrlProbe {
        @Bean
        fun requestUrlProbe() = FilterRegistrationBean(Filter { request, response, _ ->
            response.writer.write((request as HttpServletRequest).requestURL.toString())
        }).apply {
            addUrlPatterns(PROBE)
            order = SecurityFilterProperties.DEFAULT_FILTER_ORDER - 1
        }
    }

    companion object {
        private const val PROBE = "/test-probe/request-url"

        private val certDir: Path = Files.createTempDirectory("kernfolio-web-mtls").also { dir ->
            Files.list(TestPki.dir).use { files -> files.forEach { Files.copy(it, dir.resolve(it.fileName)) } }
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("openbao.certs.dir") { certDir.toString() }
            // ca.pem exists, which OptimizerClientConfig reads as "mTLS is on".
            // The optimizer hop is OptimizerMtlsIntegrationTest's job.
            registry.add("openbao.certs.ca") { "" }
            registry.add("optimizer.base-url") { "http://localhost:9999" }
            registry.add("market-data.scheduler.enabled") { "false" }
        }
    }

    @LocalServerPort var port: Int = 0

    @Test
    fun `request without a client certificate is refused during the handshake`() {
        assertThatThrownBy { get("/login", client(clientCertStem = null)) }
            .isInstanceOf(IOException::class.java)
    }

    @Test
    fun `caddy client certificate is let through`() {
        val response = get("/login", client("caddy-client"))
        assertThat(response.statusCode()).isEqualTo(200)
    }

    @Test
    fun `certificate from the same CA with another CN is forbidden`() {
        val response = get("/login", client("optimizer-client"))
        assertThat(response.statusCode()).isEqualTo(403)
    }

    @Test
    fun `the app sees the proxy's forwarded host and scheme`() {
        val response = get(
            PROBE, client("caddy-client"),
            "X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "kernfolio.example",
        )
        assertThat(response.body()).isEqualTo("https://kernfolio.example$PROBE")
    }

    @Test
    fun `redirects are relative, so they resolve against the browser's own URL`() {
        // Tomcat's (and since Boot 4.2 the default) server.tomcat.servlet.use-relative-redirects.
        val response = get(
            "/admin/users", client("caddy-client"),
            "X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "kernfolio.example",
        )
        assertThat(response.statusCode()).isEqualTo(302)
        assertThat(response.headers().firstValue("Location")).hasValue("/login")
    }

    @Test
    fun `serves a renewed app_pem without a restart`() {
        assertThat(servedSerial()).isEqualTo(serial("app"))

        // As cert-renewer's place(): key, then cert, each by rename.
        for ((source, target) in listOf(TestPki.key("app-renewed") to "app-key.pem", TestPki.cert("app-renewed") to "app.pem")) {
            val tmp = Files.createTempFile(certDir, "swap", ".tmp")
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, certDir.resolve(target), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        // reload-on-update: a 10 s quiet period, plus the file watcher's polling.
        eventually(Duration.ofSeconds(60)) { assertThat(servedSerial()).isEqualTo(serial("app-renewed")) }
        assertThat(get("/login", client("caddy-client")).statusCode()).isEqualTo(200)
    }

    private fun serial(stem: String): BigInteger = PemContent.load(TestPki.cert(stem)).getCertificates().first().serialNumber

    /** The serial of the cert a new handshake gets, with a new context: no session resumption. */
    private fun servedSerial(): BigInteger =
        (sslContext("caddy-client").socketFactory.createSocket("localhost", port) as SSLSocket).use {
            it.startHandshake()
            (it.session.peerCertificates.first() as X509Certificate).serialNumber
        }

    private fun get(path: String, client: HttpClient, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder().uri(URI.create("https://localhost:$port$path")).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun client(clientCertStem: String?): HttpClient = HttpClient.newBuilder().sslContext(sslContext(clientCertStem)).build()

    private fun sslContext(clientCertStem: String?): SSLContext {
        val keyStore = clientCertStem?.let {
            PemSslStoreDetails.forCertificate(TestPki.cert(it).toString())
                .withPrivateKey(TestPki.key(it).toString())
        }
        val trustStore = PemSslStoreDetails.forCertificate(TestPki.cert("ca").toString())
        return SslBundle.of(PemSslStoreBundle(keyStore, trustStore)).createSslContext()
    }
}
