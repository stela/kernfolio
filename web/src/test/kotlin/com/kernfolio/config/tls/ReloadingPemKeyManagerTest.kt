package com.kernfolio.config.tls

import com.kernfolio.TestPki
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemContent
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Real TLS 1.3 handshakes with [TestPki]'s Ed25519 certs: a server that
 * requires a client cert reports which one each new connection presented.
 */
class ReloadingPemKeyManagerTest {

    @TempDir lateinit var dir: Path

    private val cert get() = dir.resolve("client.pem")
    private val key get() = dir.resolve("client-key.pem")

    private val server: SSLServerSocket = SslBundle.of(
        PemSslStoreBundle(
            PemSslStoreDetails.forCertificate(TestPki.cert("app").toString()).withPrivateKey(TestPki.key("app").toString()),
            PemSslStoreDetails.forCertificate(TestPki.cert("ca").toString()),
        )
    ).createSslContext().serverSocketFactory.createServerSocket(0) as SSLServerSocket

    init {
        server.needClientAuth = true
    }

    @AfterEach
    fun close() = server.close()

    private fun install(certStem: String, keyStem: String = certStem) {
        // Replaced by rename, like place() in openbao/init/common.sh.
        for ((source, target) in listOf(TestPki.key(keyStem) to key, TestPki.cert(certStem) to cert)) {
            val tmp = Files.createTempFile(dir, "swap", ".tmp")
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING)
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    private fun serial(stem: String): BigInteger = PemContent.load(TestPki.cert(stem)).getCertificates().first().serialNumber

    /** The serial of the client cert a new connection presents. A new SSLContext each time: no session resumption. */
    private fun presented(keyManager: ReloadingPemKeyManager): BigInteger {
        val accepted = CompletableFuture.supplyAsync {
            (server.accept() as SSLSocket).use { socket ->
                socket.startHandshake()
                (socket.session.peerCertificates.first() as X509Certificate).serialNumber
            }
        }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("ca", PemContent.load(TestPki.cert("ca")).getCertificates().first())
            })
        }
        val context = SSLContext.getInstance("TLSv1.3").apply { init(arrayOf(keyManager), trust.trustManagers, null) }
        (context.socketFactory.createSocket("localhost", server.localPort) as SSLSocket).use { it.startHandshake() }
        return accepted.get(10, TimeUnit.SECONDS)
    }

    @Test
    fun `presents a renewed cert on the next handshake`() {
        install("app-client")
        val keyManager = ReloadingPemKeyManager(cert, key, Duration.ZERO)
        assertThat(presented(keyManager)).isEqualTo(serial("app-client"))

        install("app-client-renewed")

        assertThat(presented(keyManager)).isEqualTo(serial("app-client-renewed"))
        assertThat(keyManager.serialNumber).isEqualTo(serial("app-client-renewed"))
    }

    @Test
    fun `keeps the current cert while the key doesn't match yet`() {
        install("app-client")
        val keyManager = ReloadingPemKeyManager(cert, key, Duration.ZERO)

        // The renewed cert has arrived, its key not yet.
        install("app-client-renewed", keyStem = "app-client")
        assertThat(presented(keyManager)).isEqualTo(serial("app-client"))

        install("app-client-renewed")
        assertThat(presented(keyManager)).isEqualTo(serial("app-client-renewed"))
    }

    @Test
    fun `keeps the current cert while a file is missing`() {
        install("app-client")
        val keyManager = ReloadingPemKeyManager(cert, key, Duration.ZERO)
        Files.delete(key)
        assertThat(presented(keyManager)).isEqualTo(serial("app-client"))
    }

    @Test
    fun `looks at the files at most every check interval`() {
        install("app-client")
        val keyManager = ReloadingPemKeyManager(cert, key, Duration.ofHours(1))
        install("app-client-renewed")
        assertThat(presented(keyManager)).isEqualTo(serial("app-client"))
    }

    @Test
    fun `a missing or mismatched pair at start is an error`() {
        assertThatThrownBy { ReloadingPemKeyManager(cert, key) }.isInstanceOf(Exception::class.java)
        install("app-client-renewed", keyStem = "app-client")
        assertThatThrownBy { ReloadingPemKeyManager(cert, key) }.hasMessageContaining("is not the key of")
    }
}
