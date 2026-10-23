package com.kernfolio

import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.ImageFromDockerfile
import java.io.ByteArrayInputStream
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * OpenBao listener TLS material for the test containers, made by the real
 * openbao/init/setup-tls.sh in the real openbao-init image, as
 * openbao-tls-init does in compose: [dir] holds `openbao.pem`,
 * `openbao-key.pem` and the trust anchor `openbao-ca.pem`.
 *
 * Made once per test JVM and shared by every [OpenBaoContainer].
 */
object OpenBaoTls {
    const val CA = "openbao-ca.pem"
    val FILES = listOf("openbao.pem", "openbao-key.pem", CA)

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
            for (file in FILES) {
                init.copyFileFromContainer("/openbao/certs/openbao/$file", dir.resolve(file).toString())
            }
        }
    }

    /** An HTTP client that trusts only the OpenBao listener anchor in [tlsDir]. */
    fun httpClient(tlsDir: Path = dir): HttpClient = HttpClient.newBuilder().sslContext(sslContext(tlsDir)).build()

    private fun sslContext(tlsDir: Path): SSLContext {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        CertificateFactory.getInstance("X.509")
            .generateCertificates(ByteArrayInputStream(Files.readAllBytes(tlsDir.resolve(CA))))
            .forEachIndexed { i, cert -> trustStore.setCertificateEntry("openbao-ca-$i", cert) }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(trustStore) }.trustManagers
        return SSLContext.getInstance("TLSv1.3").apply { init(null, trustManagers, null) }
    }
}
