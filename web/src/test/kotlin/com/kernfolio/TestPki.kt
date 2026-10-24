package com.kernfolio

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Certificates for the mTLS integration tests, issued by a real OpenBao PKI set
 * up like openbao/init/setup.sh: two-tier, Ed25519, each leaf file bundled with
 * its intermediate. File names match the certs_* volumes; they're all in one
 * dir here, so [dir] can stand in for any service's /openbao/certs.
 *
 * `foreign-client` comes from an unrelated root CA (CN `app`, like the real
 * app cert) for "wrong CA" negative tests.
 *
 * Also holds [OpenBaoTls]'s `openbao-ca.pem` and `openbao-client.p12`, as
 * certs_web does, for Spring Cloud Vault's mTLS to OpenBao.
 *
 * Started once per test JVM and shared by all test classes.
 */
object TestPki {
    val dir: Path = Files.createTempDirectory("kernfolio-test-pki")

    private const val TOKEN = "test-pki-token"
    private val json = JsonMapper.builder().build()
    private val openBao = OpenBaoContainer(TOKEN)

    init {
        for (file in listOf(OpenBaoTls.CA, OpenBaoTls.WEB_P12)) {
            Files.copy(OpenBaoTls.dir.resolve(file), dir.resolve(file))
        }
        openBao.start()
        openBao.bao("secrets", "enable", "pki")
        openBao.bao("secrets", "enable", "-path=pki_int", "pki")
        openBao.bao("secrets", "enable", "-path=pki_foreign", "pki")

        val root = write("pki/root/generate/internal", mapOf(
            "common_name" to "Kernfolio Test CA", "key_type" to "ed25519", "ttl" to "24h",
        ))
        Files.writeString(dir.resolve("ca.pem"), root["certificate"].asString())

        val csr = write("pki_int/intermediate/generate/internal", mapOf(
            "common_name" to "Kernfolio Test Intermediate CA", "key_type" to "ed25519",
        ))["csr"].asString()
        val intermediate = write("pki/root/sign-intermediate", mapOf(
            "csr" to csr, "format" to "pem_bundle", "ttl" to "12h",
        ))["certificate"].asString()
        write("pki_int/intermediate/set-signed", mapOf("certificate" to intermediate))
        write("pki_int/roles/kernfolio-service", role("app,web,optimizer,postgres,caddy,kernfolio,localhost"))

        // Same leaves as setup.sh's issue_cert calls.
        issue("pki_int", "app", "web,localhost", "app")
        issue("pki_int", "app", null, "app-client")
        issue("pki_int", "optimizer", "localhost", "optimizer")
        issue("pki_int", "optimizer", null, "optimizer-client")
        issue("pki_int", "caddy", null, "caddy-client")
        issue("pki_int", "postgres", "localhost", "postgres")
        issue("pki_int", "kernfolio", null, "postgres-admin-client")

        write("pki_foreign/root/generate/internal", mapOf(
            "common_name" to "Unrelated CA", "key_type" to "ed25519", "ttl" to "24h",
        ))
        write("pki_foreign/roles/kernfolio-service", role("app"))
        issue("pki_foreign", "app", null, "foreign-client")
    }

    fun cert(stem: String): Path = dir.resolve("$stem.pem")
    fun key(stem: String): Path = dir.resolve("$stem-key.pem")

    private fun role(allowedDomains: String) = mapOf(
        "allowed_domains" to allowedDomains,
        "allow_subdomains" to false,
        "allow_bare_domains" to true,
        "allow_localhost" to true,
        "key_type" to "ed25519",
        "max_ttl" to "6h",
    )

    /** Same shape as issue_cert() in setup.sh: leaf + issuing CA, PKCS#8 key. */
    private fun issue(mount: String, cn: String, altNames: String?, stem: String) {
        val body = mutableMapOf<String, Any>("common_name" to cn, "ttl" to "1h", "private_key_format" to "pkcs8")
        altNames?.let { body["alt_names"] = it }
        val data = write("$mount/issue/kernfolio-service", body)
        Files.writeString(cert(stem), data["certificate"].asString() + "\n" + data["issuing_ca"].asString() + "\n")
        // 0600 like openbao/init/setup.sh writes keys (pgjdbc and libpq insist).
        Files.writeString(key(stem), data["private_key"].asString())
        Files.setPosixFilePermissions(key(stem), PosixFilePermissions.fromString("rw-------"))
    }

    private fun write(path: String, body: Map<String, Any>): JsonNode {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("${openBao.address}/v1/$path"))
            .header("X-Vault-Token", TOKEN)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build()
        val response = openBao.httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        check(response.statusCode() in 200..299) { "bao write $path failed: ${response.body()}" }
        return if (response.body().isBlank()) json.createObjectNode() else json.readTree(response.body())["data"]
    }
}
