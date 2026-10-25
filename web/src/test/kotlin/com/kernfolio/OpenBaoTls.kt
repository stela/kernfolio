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
 * ([OPENBAO_FILES]) plus web's client cert and key, as [WEB_CERT] and
 * [WEB_KEY] (in certs_web they're openbao-client.pem and -key.pem, the same
 * names as the CLI's).
 *
 * Made once per test JVM and shared by every [OpenBaoContainer].
 */
object OpenBaoTls {
    const val CA = "openbao-ca.pem"
    /** Prefix of the renewed copies in [dir]: same CA, new server and web client leaves. */
    const val RENEWED = "renewed-"
    const val WEB_CERT = "web-openbao-client.pem"
    const val WEB_KEY = "web-openbao-client-key.pem"
    /** [WEB_CERT] and [WEB_KEY] by their names in certs_web. */
    val WEB_FILES = mapOf(WEB_CERT to "openbao-client.pem", WEB_KEY to "openbao-client-key.pem")
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
            val result = init.execInContainer("sh", "-c", "mkdir -p /openbao/certs/openbao /openbao/certs/web /openbao/tls-ca && /openbao/init/setup-tls.sh")
            check(result.exitCode == 0) { "setup-tls.sh failed: ${result.stdout}\n${result.stderr}" }
            val base64Of = { path: String -> init.execInContainer("base64", path).also { check(it.exitCode == 0) { it.stderr } }.stdout }
            copyOut(dir, base64Of = base64Of)
            // Renewed leaves from the same CA, as cert-renewer makes them, for the reload tests.
            val renewal = init.execInContainer("sh", "-c", "rm /openbao/certs/openbao/openbao.pem /openbao/certs/web/openbao-client.pem && /openbao/init/setup-tls.sh --leaves-only")
            check(renewal.exitCode == 0) { "setup-tls.sh --leaves-only failed: ${renewal.stdout}\n${renewal.stderr}" }
            copyOut(dir, RENEWED, base64Of)
        }
    }

    /** Copies openbao/init (scripts and policies) into [container], as compose mounts it. */
    fun copyInitScripts(container: GenericContainer<*>) {
        for (script in listOf("common.sh", "pki-certs.sh")) {
            container.withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/$script"), "/openbao/init/$script")
        }
        for (script in listOf("setup-tls.sh", "setup.sh", "renew.sh")) {
            container.withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/$script", 0b111_101_101), "/openbao/init/$script")
        }
        for (policy in listOf("kernfolio-app", "kernfolio-cert-renewer")) {
            container.withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/policies/$policy.hcl"), "/openbao/init/policies/$policy.hcl")
        }
    }

    /**
     * Copies certs_openbao's files and web's client cert and key from a
     * container where setup-tls.sh ran into [target], reading each through
     * [base64Of] (path -> base64 of its bytes): `docker cp` doesn't see tmpfs
     * mounts.
     */
    fun copyOut(target: Path, prefix: String = "", base64Of: (String) -> String) {
        val sources = OPENBAO_FILES.associateWith { "/openbao/certs/openbao/$it" } +
            WEB_FILES.mapValues { "/openbao/certs/web/${it.value}" }
        for ((file, source) in sources) {
            Files.write(target.resolve(prefix + file), Base64.getMimeDecoder().decode(base64Of(source).trim()))
        }
    }

    /** Web's client cert and key, as a key store. */
    fun webKeyStore(tlsDir: Path = dir): KeyStore = pemKeyStore(tlsDir.resolve(WEB_CERT), tlsDir.resolve(WEB_KEY))

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

    /** A new context each time, so no TLS session is resumed across calls. */
    fun sslContext(tlsDir: Path = dir, clientKeys: KeyStore? = webKeyStore(tlsDir)): SSLContext {
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
