package com.kernfolio

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.IOException
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
 * bundles accept what production is issued.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("tls")
class WebMtlsIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("openbao.certs.dir") { TestPki.dir.toString() }
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
    fun `redirects honour the proxy's forwarded host and scheme`() {
        val response = get(
            "/admin/users", client("caddy-client"),
            "X-Forwarded-Proto" to "https", "X-Forwarded-Host" to "kernfolio.example",
        )
        assertThat(response.statusCode()).isEqualTo(302)
        assertThat(response.headers().firstValue("Location")).hasValue("https://kernfolio.example/login")
    }

    private fun get(path: String, client: HttpClient, vararg headers: Pair<String, String>): HttpResponse<String> {
        val request = HttpRequest.newBuilder().uri(URI.create("https://localhost:$port$path")).GET()
        headers.forEach { (name, value) -> request.header(name, value) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun client(clientCertStem: String?): HttpClient {
        val keyStore = clientCertStem?.let {
            PemSslStoreDetails.forCertificate(TestPki.cert(it).toString())
                .withPrivateKey(TestPki.key(it).toString())
        }
        val trustStore = PemSslStoreDetails.forCertificate(TestPki.cert("ca").toString())
        val sslContext = SslBundle.of(PemSslStoreBundle(keyStore, trustStore)).createSslContext()
        return HttpClient.newBuilder().sslContext(sslContext).build()
    }
}
