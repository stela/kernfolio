package com.kernfolio

import org.assertj.core.api.Assertions.assertThat
import com.github.dockerjava.api.model.Capability
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.images.builder.ImageFromDockerfile
import java.nio.file.Path

private const val BAO_TOKEN = "init-test-token"

/**
 * Runs the real openbao/init/setup.sh in the real openbao-init image, with
 * compose's user and capabilities (root with only CHOWN + DAC_READ_SEARCH,
 * no-new-privileges),
 * and checks each per-service certs volume: it holds exactly that service's
 * files, and every private key belongs to the service's uid at 0600.
 */
class OpenBaoInitIntegrationTest {

    companion object {
        // Not `network`: inside GenericContainer.apply {} that name would resolve to
        // the container's own (unset) network property.
        private val sharedNetwork = Network.newNetwork()

        private val openBao = OpenBaoContainer(BAO_TOKEN)
            .withNetwork(sharedNetwork)
            .withNetworkAliases("openbao")

        private const val CA = "0:0 644"

        /** volume dir -> file name -> "uid:gid mode" */
        private val expected = mapOf(
            "web" to mapOf(
                "ca.pem" to CA,
                "app.pem" to "0:0 644", "app-key.pem" to "1002:0 600",
                "app-client.pem" to "0:0 644", "app-client-key.pem" to "1002:0 600",
            ),
            "caddy" to mapOf("ca.pem" to CA, "caddy-client.pem" to "0:0 644", "caddy-client-key.pem" to "1000:0 600"),
            "optimizer" to mapOf(
                "ca.pem" to CA,
                "optimizer.pem" to "0:0 644", "optimizer-key.pem" to "10001:0 600",
                "optimizer-client.pem" to "0:0 644", "optimizer-client-key.pem" to "10001:0 600",
            ),
            "postgres" to mapOf(
                "ca.pem" to CA,
                "postgres.pem" to "0:0 644", "postgres-key.pem" to "70:0 600",
                "pg_root_password" to "70:0 600",
            ),
            "openbao" to mapOf(
                "ca.pem" to CA,
                "postgres-admin-client.pem" to "0:0 644", "postgres-admin-client-key.pem" to "100:0 600",
                "pg_root_password" to "100:0 600",
            ),
        )

        private val image = ImageFromDockerfile("kernfolio-openbao-init-test", false)
            .withFileFromPath("Dockerfile", Path.of("..", "openbao", "Dockerfile").toAbsolutePath().normalize())

        private val init = GenericContainer(image).apply {
            withNetwork(sharedNetwork)
            withEnv("BAO_ADDR", "http://openbao:8200")
            withEnv("BAO_TOKEN", BAO_TOKEN)
            withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/setup.sh", 0b111_101_101), "/openbao/init/setup.sh")
            // Like fresh certs_<service> volumes: root-owned, empty mounts.
            withTmpFs(expected.keys.associate { "/openbao/certs/$it" to "rw,mode=755" })
            // Stay up so the script can be run (twice) with execInContainer.
            withCommand("tail", "-f", "/dev/null")
            withCreateContainerCmdModifier { cmd ->
                cmd.withUser("0:0")
                cmd.hostConfig!!
                    .withCapDrop(Capability.ALL)
                    .withCapAdd(Capability.CHOWN, Capability.DAC_READ_SEARCH)
                    .withSecurityOpts(listOf("no-new-privileges:true"))
            }
        }

        @JvmStatic
        @BeforeAll
        fun start() {
            openBao.start()
            init.start()
        }

        private fun exec(vararg command: String): String {
            val result = init.execInContainer(*command)
            check(result.exitCode == 0) { "${command.joinToString(" ")} failed: ${result.stdout}\n${result.stderr}" }
            return result.stdout
        }

        // setup.sh waits for OpenBao forever; bound it so a broken setup fails.
        private fun runSetup() = exec("timeout", "120", "/openbao/init/setup.sh")

        private fun ownership(): Map<String, Map<String, String>> = expected.keys.associateWith { dir ->
            exec("sh", "-c", "cd /openbao/certs/$dir && stat -c '%n %u:%g %a' *").lines()
                .filter { it.isNotBlank() }
                .associate { line -> line.substringBefore(' ') to line.substringAfter(' ') }
        }
    }

    @Test
    fun `each volume holds only its service's files, keys owned by it at 0600, also after a rerun`() {
        runSetup()
        assertThat(ownership()).isEqualTo(expected)
        val caBefore = exec("cat", "/openbao/certs/web/ca.pem")
        val passwordBefore = exec("cat", "/openbao/certs/openbao/pg_root_password")
        assertThat(exec("cat", "/openbao/certs/postgres/pg_root_password")).isEqualTo(passwordBefore)

        // A rerun has to replace files that now belong to other users, with no
        // DAC_OVERRIDE. It must also keep the CA and the postgres password.
        val output = runSetup()
        assertThat(output).contains("Reusing existing root CA", "Reusing existing pg_root_password")
        assertThat(ownership()).isEqualTo(expected)
        assertThat(exec("cat", "/openbao/certs/web/ca.pem")).isEqualTo(caBefore)
        assertThat(exec("cat", "/openbao/certs/openbao/pg_root_password")).isEqualTo(passwordBefore)
        assertThat(exec("cat", "/openbao/certs/postgres/pg_root_password")).isEqualTo(passwordBefore)
    }
}
