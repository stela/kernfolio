package com.kernfolio

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * The dev OpenBao (openbao.hcl + listener.hcl + dev.hcl, as in
 * docker-compose.dev.yml): it unseals and initializes itself, keeps its state
 * across restarts (so the PKI root, and every cert issued from it, stays
 * valid), and listens only on the TLS API port and Raft's cluster port.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenBaoDevServerTest {

    private val openBao = OpenBaoContainer("dev-server-test-token")

    @BeforeAll
    fun start() = openBao.start()

    @AfterAll
    fun stop() = openBao.stop()

    private fun bao(vararg args: String): String {
        val result = openBao.execInContainer("bao", *args)
        check(result.exitCode == 0) { "bao ${args.joinToString(" ")} failed: ${result.stderr}" }
        return result.stdout.trim()
    }

    @Test
    fun `self-init gives the configured token root, without -dev`() {
        assertThat(bao("token", "lookup", "-field=policies")).isEqualTo("[root]")
        assertThat(bao("token", "lookup", "-field=orphan")).isEqualTo("true")
        assertThat(openBao.execInContainer("ps", "-o", "args").stdout).doesNotContain(" -dev")
    }

    @Test
    fun `listens only on 8200 (TLS) and the Raft cluster port`() {
        // LISTEN sockets (state 0A) from /proc, as the image has no netstat/ss.
        val listening = openBao.execInContainer("sh", "-c", "cat /proc/net/tcp /proc/net/tcp6").stdout.lines()
            .map { it.trim().split(Regex("\\s+")) }
            .filter { it.size > 3 && it[3] == "0A" }
            .map { it[1].substringAfterLast(':').toInt(16) }
            .toSet()
        assertThat(listening).containsExactlyInAnyOrder(OpenBaoContainer.PORT, 8201)
    }

    @Test
    fun `a restart keeps the PKI root, KV and the token`() {
        bao("secrets", "enable", "-path=restart-pki", "pki")
        val root = bao("write", "-field=certificate", "restart-pki/root/generate/internal", "common_name=restart-test", "ttl=1h")
        bao("secrets", "enable", "-path=restart-kv", "-version=2", "kv")
        bao("kv", "put", "restart-kv/x", "a=1")

        openBao.dockerClient.restartContainerCmd(openBao.containerId).exec()
        // Unsealed again by the static seal; self-init doesn't run a second time.
        val deadline = System.nanoTime() + 60_000_000_000
        while (openBao.execInContainer("bao", "token", "lookup").exitCode != 0) {
            check(System.nanoTime() < deadline) { "OpenBao didn't come back after a restart" }
            Thread.sleep(500)
        }

        assertThat(bao("read", "-field=certificate", "restart-pki/cert/ca")).isEqualTo(root)
        assertThat(bao("kv", "get", "-field=a", "restart-kv/x")).isEqualTo("1")
    }
}
