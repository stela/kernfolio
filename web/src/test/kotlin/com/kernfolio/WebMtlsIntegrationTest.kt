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
import org.testcontainers.vault.VaultContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path

private const val VAULT_TOKEN = "test-root-token"

/**
 * Exercises the `tls` profile (application-tls.yml + ClientCertCnFilter) — the
 * Caddy -> web hop — which every other test skips by running plain HTTP.
 *
 * Certificates come from a real Vault PKI configured like vault/init/setup.sh
 * (two-tier, Ed25519, leaf bundled with its intermediate), so this also proves
 * that Tomcat/JSSE and Spring's PEM bundles accept what production is issued.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration::class)
@ActiveProfiles("tls")
class WebMtlsIntegrationTest {

    companion object {
        private val json = JsonMapper.builder().build()
        private val certDir: Path = Files.createTempDirectory("kernfolio-mtls-test")

        private val vault = VaultContainer("hashicorp/vault:1.19")
            .withVaultToken(VAULT_TOKEN)
            .withInitCommand("secrets enable pki", "secrets enable -path=pki_int pki")

        init {
            vault.start()

            val root = vaultWrite("pki/root/generate/internal", mapOf(
                "common_name" to "Kernfolio Test CA", "key_type" to "ed25519", "ttl" to "24h",
            ))
            Files.writeString(certDir.resolve("ca.pem"), root["certificate"].asString())

            val csr = vaultWrite("pki_int/intermediate/generate/internal", mapOf(
                "common_name" to "Kernfolio Test Intermediate CA", "key_type" to "ed25519",
            ))["csr"].asString()
            val intermediate = vaultWrite("pki/root/sign-intermediate", mapOf(
                "csr" to csr, "format" to "pem_bundle", "ttl" to "12h",
            ))["certificate"].asString()
            vaultWrite("pki_int/intermediate/set-signed", mapOf("certificate" to intermediate))

            vaultWrite("pki_int/roles/kernfolio-service", mapOf(
                "allowed_domains" to "app,web,optimizer,postgres,caddy,localhost",
                "allow_subdomains" to false,
                "allow_bare_domains" to true,
                "allow_localhost" to true,
                "key_type" to "ed25519",
                "max_ttl" to "6h",
            ))

            issueCert("app", "web,localhost", "app")
            issueCert("caddy", null, "caddy-client")
            issueCert("optimizer", null, "optimizer-client")
        }

        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            registry.add("vault.certs.dir") { certDir.toString() }
            // ca.pem now exists, which OptimizerClientConfig reads as "mTLS is
            // on" and would go looking for app-client.pem. Not under test here.
            registry.add("vault.certs.ca") { "" }
            registry.add("optimizer.base-url") { "http://localhost:9999" }
            registry.add("market-data.scheduler.enabled") { "false" }
        }

        /** Same shape as issue_cert() in setup.sh: leaf + issuing intermediate, PKCS#8 key. */
        private fun issueCert(cn: String, altNames: String?, fileStem: String) {
            val body = mutableMapOf<String, Any>("common_name" to cn, "ttl" to "1h", "private_key_format" to "pkcs8")
            altNames?.let { body["alt_names"] = it }
            val data = vaultWrite("pki_int/issue/kernfolio-service", body)
            Files.writeString(
                certDir.resolve("$fileStem.pem"),
                data["certificate"].asString() + "\n" + data["issuing_ca"].asString() + "\n",
            )
            Files.writeString(certDir.resolve("$fileStem-key.pem"), data["private_key"].asString())
        }

        private fun vaultWrite(path: String, body: Map<String, Any>): JsonNode {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("http://${vault.host}:${vault.firstMappedPort}/v1/$path"))
                .header("X-Vault-Token", VAULT_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "vault write $path failed: ${response.body()}" }
            return if (response.body().isBlank()) json.createObjectNode() else json.readTree(response.body())["data"]
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
            PemSslStoreDetails.forCertificate(certDir.resolve("$it.pem").toString())
                .withPrivateKey(certDir.resolve("$it-key.pem").toString())
        }
        val trustStore = PemSslStoreDetails.forCertificate(certDir.resolve("ca.pem").toString())
        val sslContext = SslBundle.of(PemSslStoreBundle(keyStore, trustStore)).createSslContext()
        return HttpClient.newBuilder().sslContext(sslContext).build()
    }
}
