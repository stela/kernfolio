package com.kernfolio

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName

/**
 * OpenBao dev server (in-memory, unsealed, fixed root token) for integration tests.
 *
 * Testcontainers' VaultContainer only accepts `hashicorp/vault` images, so this
 * is a plain GenericContainer. Unlike VaultContainer's init commands, [bao]
 * fails the test on a non-zero exit instead of only logging it.
 */
class OpenBaoContainer(val token: String) :
    GenericContainer<OpenBaoContainer>(DockerImageName.parse("openbao/openbao:2.7.1")) {

    init {
        withExposedPorts(PORT)
        withCommand("bao", "server", "-dev", "-dev-root-token-id=$token", "-dev-listen-address=0.0.0.0:$PORT")
        // Read by `bao` CLI invocations through execInContainer.
        withEnv("BAO_ADDR", "http://127.0.0.1:$PORT")
        withEnv("BAO_TOKEN", token)
        waitingFor(Wait.forHttp("/v1/sys/health").forStatusCode(200))
    }

    /** Base URL as seen from the test JVM. */
    val address: String get() = "http://$host:${getMappedPort(PORT)}"

    /** Runs `bao <args>` inside the container. */
    fun bao(vararg args: String) {
        val result = execInContainer("bao", *args)
        check(result.exitCode == 0) { "bao ${args.joinToString(" ")} failed: ${result.stderr}" }
    }

    private companion object {
        const val PORT = 8200
    }
}
