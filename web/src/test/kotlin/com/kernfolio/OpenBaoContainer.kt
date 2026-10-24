package com.kernfolio

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.net.http.HttpClient
import java.nio.file.Path

/**
 * OpenBao dev server (in-memory, unsealed, fixed root token) for integration
 * tests, started like docker-compose.dev.yml: its API on 8200 over mutual
 * TLS, from the real openbao/config/listener.hcl and certs made by
 * setup-tls.sh ([tlsDir], by default [OpenBaoTls.dir]). Dev mode's own
 * plain-HTTP listener stays on loopback inside the container.
 *
 * Testcontainers' VaultContainer only accepts `hashicorp/vault` images, so this
 * is a plain GenericContainer. Unlike VaultContainer's init commands, [bao]
 * fails the test on a non-zero exit instead of only logging it.
 */
class OpenBaoContainer(val token: String, private val tlsDir: Path = OpenBaoTls.dir) :
    GenericContainer<OpenBaoContainer>(DockerImageName.parse("openbao/openbao:2.7.1")) {

    init {
        withExposedPorts(PORT)
        withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/config/listener.hcl"), "/openbao/config/listener.hcl")
        // On the volume the keys are openbao-owned 0600; these root-owned
        // copies are 0644 so the `openbao` user can read them.
        for (file in OpenBaoTls.OPENBAO_FILES) {
            withCopyFileToContainer(MountableFile.forHostPath(tlsDir.resolve(file), 0b110_100_100), "/openbao/certs/$file")
        }
        withCommand(
            "bao", "server", "-dev", "-dev-root-token-id=$token",
            "-dev-listen-address=127.0.0.1:8210", "-config=/openbao/config/listener.hcl",
        )
        // Read by `bao` CLI invocations through execInContainer.
        withEnv("BAO_ADDR", "https://127.0.0.1:$PORT")
        withEnv("BAO_CACERT", "/openbao/certs/${OpenBaoTls.CA}")
        withEnv("BAO_CLIENT_CERT", "/openbao/certs/openbao-client.pem")
        withEnv("BAO_CLIENT_KEY", "/openbao/certs/openbao-client-key.pem")
        withEnv("BAO_TOKEN", token)
        // Like compose's healthcheck. An HTTP wait can't present a client cert.
        waitingFor(Wait.forSuccessfulCommand("bao status"))
    }

    /** Base URL as seen from the test JVM. */
    val address: String get() = "https://$host:${getMappedPort(PORT)}"

    /** A client that trusts this server's listener cert, and nothing else, and presents web's client cert. */
    val httpClient: HttpClient by lazy { OpenBaoTls.httpClient(tlsDir) }

    /** Runs `bao <args>` inside the container. */
    fun bao(vararg args: String) {
        val result = execInContainer("bao", *args)
        check(result.exitCode == 0) { "bao ${args.joinToString(" ")} failed: ${result.stderr}" }
    }

    companion object {
        const val PORT = 8200
    }
}
