package com.kernfolio

import com.kernfolio.config.OptimizerClientConfig
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.images.builder.ImageFromDockerfile
import org.testcontainers.utility.MountableFile
import org.yaml.snakeyaml.Yaml
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import javax.net.ssl.SSLContext

/**
 * The web -> optimizer hop against the real optimizer image (optimizer/Dockerfile,
 * run_mtls.py) with [TestPki] certs at /openbao/certs: the server must demand a
 * client cert from the OpenBao CA, and web's client must present one and verify
 * the server's host name.
 */
class OptimizerMtlsIntegrationTest {

    companion object {
        private val optimizerDir: Path = Path.of("..", "optimizer").toAbsolutePath().normalize()

        // Only what the Dockerfile copies: the context stays small (no local
        // .venv) and Docker's layer cache keeps reruns fast.
        private val image = ImageFromDockerfile("kernfolio-optimizer-mtls-test", false).apply {
            for (file in listOf("Dockerfile", "pyproject.toml", "uv.lock", "run_mtls.py", "app")) {
                withFileFromPath(file, optimizerDir.resolve(file))
            }
        }

        private val optimizer = GenericContainer(image).apply {
            withExposedPorts(8000)
            for (file in listOf("ca.pem", "optimizer.pem", "optimizer-key.pem", "optimizer-client.pem", "optimizer-client-key.pem")) {
                // Readable by the image's unprivileged user, as on the openbao_certs volume.
                withCopyFileToContainer(MountableFile.forHostPath(TestPki.dir.resolve(file), 0b110_100_100), "/openbao/certs/$file")
            }
            // run_mtls.py starts 2 workers; the parent logs "Uvicorn running on
            // https://..." before they can serve, and early requests get reset.
            waitingFor(Wait.forLogMessage(".*Application startup complete.*", 2))
        }

        @JvmStatic
        @BeforeAll
        fun start() = optimizer.start()

        private fun url(host: String = "localhost") = "https://$host:${optimizer.getMappedPort(8000)}"

        private fun webClient(host: String = "localhost") = OptimizerClientConfig().optimizerWebClient(
            url(host),
            TestPki.cert("ca").toString(),
            TestPki.cert("app-client").toString(),
            TestPki.key("app-client").toString(),
        )

        private fun getHealth(sslContext: SSLContext): Int {
            val client = HttpClient.newBuilder().sslContext(sslContext).build()
            val request = HttpRequest.newBuilder(URI.create("${url()}/health")).GET().build()
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
        }
    }

    @Test
    fun `web's optimizer client connects with the app certificate`() {
        val response = webClient().get().uri("/health").retrieve().toBodilessEntity().block()
        assertThat(response!!.statusCode.value()).isEqualTo(200)
    }

    // The refusals below only see a closed connection: uvicorn drops a failed
    // client-cert handshake without a TLS alert the JDK client would surface.
    // This control, built the same way but with the legitimate cert, proves the
    // same client path otherwise works.
    @Test
    fun `plain JDK client with the app certificate gets through`() {
        val app = ForcedClientCert.sslContext(TestPki.cert("app-client"), TestPki.key("app-client"), TestPki.cert("ca"))
        assertThat(getHealth(app)).isEqualTo(200)
    }

    @Test
    fun `request without a client certificate is refused`() {
        val trustOnly = SslBundle.of(
            PemSslStoreBundle(null, PemSslStoreDetails.forCertificate(TestPki.cert("ca").toString()))
        ).createSslContext()
        assertThatThrownBy { getHealth(trustOnly) }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `client certificate from another CA is refused`() {
        val foreign = ForcedClientCert.sslContext(TestPki.cert("foreign-client"), TestPki.key("foreign-client"), TestPki.cert("ca"))
        assertThatThrownBy { getHealth(foreign) }.isInstanceOf(IOException::class.java)
    }

    @Test
    fun `web's optimizer client verifies the server certificate's host name`() {
        // 127.0.0.1 isn't in the optimizer cert's SANs (optimizer, localhost).
        assertThatThrownBy { webClient("127.0.0.1").get().uri("/health").retrieve().toBodilessEntity().block() }
            .isInstanceOf(WebClientRequestException::class.java)
            .rootCause().hasMessageContaining("127.0.0.1")
    }

    @Test
    fun `compose healthcheck passes against the mTLS server`() {
        val compose = Files.newBufferedReader(Path.of("..", "docker-compose.yml")).use { Yaml().load<Map<String, Any>>(it) }

        @Suppress("UNCHECKED_CAST")
        val optimizerService = (compose["services"] as Map<String, Any>)["optimizer"] as Map<String, Any>

        @Suppress("UNCHECKED_CAST")
        val test = (optimizerService["healthcheck"] as Map<String, Any>)["test"] as List<String>
        assertThat(test.first()).isEqualTo("CMD")

        val result = optimizer.execInContainer(*test.drop(1).toTypedArray())
        assertThat(result.exitCode).withFailMessage { "healthcheck failed: ${result.stderr}" }.isZero()
    }
}
