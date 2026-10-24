package com.kernfolio

import org.springframework.boot.ssl.pem.PemContent
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Mutual-TLS material for OpenBao's listener in the test containers, made by
 * the real openbao/init/setup-tls.sh in the real openbao-init image, as
 * openbao-tls-init does in compose. [dir] holds certs_openbao's files
 * ([OPENBAO_FILES]) plus web's [WEB_P12].
 *
 * Made once per test JVM and shared by every [OpenBaoContainer].
 */
object OpenBaoTls {
    const val CA = "openbao-ca.pem"
    const val WEB_P12 = "openbao-client.p12"
    val OPENBAO_FILES = listOf("openbao.pem", "openbao-key.pem", "openbao-client.pem", "openbao-client-key.pem", CA)

    /** The openbao-init image (openbao/Dockerfile), shared with the init script tests. */
    val initImage: ImageFromDockerfile = ImageFromDockerfile("kernfolio-openbao-init-test", false)
        .withFileFromPath("Dockerfile", Path.of("..", "openbao", "Dockerfile").toAbsolutePath().normalize())

    val dir: Path = Files.createTempDirectory("kernfolio-test-openbao-tls")

    init {
        GenericContainer(initImage).use { init ->
            init.withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/common.sh"), "/openbao/init/common.sh")
            init.withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/setup-tls.sh", 0b111_101_101), "/openbao/init/setup-tls.sh")
            init.withCommand("tail", "-f", "/dev/null")
            init.withCreateContainerCmdModifier { it.withUser("0:0") }
            init.start()
            val result = init.execInContainer("sh", "-c", "mkdir -p /openbao/certs/openbao /openbao/certs/web && /openbao/init/setup-tls.sh")
            check(result.exitCode == 0) { "setup-tls.sh failed: ${result.stdout}\n${result.stderr}" }
            copyOut(dir) { path -> init.execInContainer("base64", path).also { check(it.exitCode == 0) { it.stderr } }.stdout }
        }
    }

    /**
     * Copies certs_openbao's files and web's PKCS#12 from a container where
     * setup-tls.sh ran into [target], reading each through [base64Of] (path ->
     * base64 of its bytes): `docker cp` doesn't see tmpfs mounts.
     */
    fun copyOut(target: Path, base64Of: (String) -> String) {
        val sources = OPENBAO_FILES.associateWith { "/openbao/certs/openbao/$it" } + (WEB_P12 to "/openbao/certs/web/$WEB_P12")
        for ((file, source) in sources) {
            Files.write(target.resolve(file), Base64.getMimeDecoder().decode(base64Of(source).trim()))
        }
    }

    /**
     * Web's client key store, loaded like Spring Vault does with no
     * key-store-password: a null store password, then an empty key password.
     */
    fun webKeyStore(tlsDir: Path = dir): KeyStore = KeyStore.getInstance("PKCS12").apply {
        Files.newInputStream(tlsDir.resolve(WEB_P12)).use { load(it, null) }
    }

    /** A key store holding a PEM cert chain + key, e.g. one of [TestPki]'s. */
    fun pemKeyStore(cert: Path, key: Path): KeyStore = KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        setKeyEntry(
            "client",
            PemContent.load(key).getPrivateKey(),
            CharArray(0),
            PemContent.load(cert).getCertificates().toTypedArray(),
        )
    }

    /**
     * An HTTP client that trusts only the OpenBao listener anchor in [tlsDir]
     * and presents [clientKeys] (by default web's client cert; null for none).
     */
    fun httpClient(tlsDir: Path = dir, clientKeys: KeyStore? = webKeyStore(tlsDir)): HttpClient =
        HttpClient.newBuilder().sslContext(sslContext(tlsDir, clientKeys)).build()

    private fun sslContext(tlsDir: Path, clientKeys: KeyStore?): SSLContext {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(Files.readAllBytes(tlsDir.resolve(CA))))
            .forEachIndexed { i, cert -> trustStore.setCertificateEntry("openbao-ca-$i", cert) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trustStore) }.trustManagers
        val keyManagers = clientKeys?.let {
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(it, CharArray(0)) }.keyManagers
        }
        return SSLContext.getInstance("TLSv1.3").apply { init(keyManagers, trustManagers, null) }
    }
}
