package com.kernfolio

import org.assertj.core.api.Assertions.assertThat
import com.github.dockerjava.api.model.Capability
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import java.nio.file.Files

private const val INIT_TOKEN = "init-test-token"

/**
 * Runs the real openbao/init/setup-tls.sh and setup.sh in the real
 * openbao-init image, in compose's order and with compose's user and
 * capabilities (root with only CHOWN + DAC_READ_SEARCH, no-new-privileges):
 * setup-tls.sh first, then OpenBao starts with the listener certs it made, then
 * setup.sh talks to it over mutual TLS. Checks each per-service certs volume: it holds
 * exactly that service's files, and every private key belongs to the service's
 * uid at 0600. Also the token bootstrap: with BAO_INIT_TOKEN it mints web's and
 * cert-renewer's scoped tokens; without, it only issues certs, with the renewer's.
 * Renewal of the listener certs is in [OpenBaoTlsInitIntegrationTest].
 */
class OpenBaoInitIntegrationTest {

    companion object {
        // Not `network`: inside GenericContainer.apply {} that name would resolve to
        // the container's own (unset) network property.
        private val sharedNetwork = Network.newNetwork()

        private lateinit var openBao: OpenBaoContainer

        private const val CA = "0:0 644"

        /** volume dir -> file name -> "uid:gid mode" */
        private val expected = mapOf(
            "web" to mapOf(
                "ca.pem" to CA, "openbao-ca.pem" to CA,
                "openbao-client.pem" to "0:0 644", "openbao-client-key.pem" to "1002:0 600",
                "openbao-token" to "1002:0 600",
                "app.pem" to "0:0 644", "app-key.pem" to "1002:0 600",
                "app-client.pem" to "0:0 644", "app-client-key.pem" to "1002:0 600",
            ),
            "caddy" to mapOf("ca.pem" to CA, "caddy-client.pem" to "0:0 644", "caddy-client-key.pem" to "1000:0 600"),
            "optimizer" to mapOf(
                "ca.pem" to CA,
                "optimizer.pem" to "0:0 644", "optimizer-key.pem" to "65532:0 600",
                "optimizer-client.pem" to "0:0 644", "optimizer-client-key.pem" to "65532:0 600",
            ),
            "postgres" to mapOf(
                "ca.pem" to CA,
                "postgres.pem" to "0:0 644", "postgres-key.pem" to "70:0 600",
                "pg_root_password" to "70:0 600",
            ),
            "openbao" to mapOf(
                "ca.pem" to CA, "openbao-ca.pem" to CA,
                "openbao.pem" to "0:0 644", "openbao-key.pem" to "100:0 600",
                "openbao-client.pem" to "0:0 644", "openbao-client-key.pem" to "100:0 600",
                "postgres-admin-client.pem" to "0:0 644", "postgres-admin-client-key.pem" to "100:0 600",
                "pg_root_password" to "100:0 600",
            ),
            // lock: pki-certs.sh's, against openbao-init and cert-renewer issuing at once.
            "renewer" to mapOf("openbao-token" to "0:0 600", "lock" to "0:0 600"),
        )

        private const val WEB_TOKEN = "/openbao/certs/web/openbao-token"
        private const val RENEWER_TOKEN = "/openbao/certs/renewer/openbao-token"

        private val init = GenericContainer(OpenBaoTls.initImage).apply {
            withNetwork(sharedNetwork)
            // As in docker-compose.yml.
            withEnv("BAO_ADDR", "https://openbao:8200")
            withEnv("BAO_CACERT", "/openbao/certs/openbao/openbao-ca.pem")
            withEnv("BAO_CLIENT_CERT", "/openbao/certs/openbao/openbao-client.pem")
            withEnv("BAO_CLIENT_KEY", "/openbao/certs/openbao/openbao-client-key.pem")
            withEnv("BAO_INIT_TOKEN", INIT_TOKEN)
            OpenBaoTls.copyInitScripts(this)
            // Like fresh certs_<service> volumes (and openbao_tls_ca): root-owned, empty mounts.
            withTmpFs(expected.keys.associate { "/openbao/certs/$it" to "rw,mode=755" } + ("/openbao/tls-ca" to "rw,mode=755"))
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
            init.start()
            exec("/openbao/init/setup-tls.sh")
            // OpenBao's mTLS certs, as setup-tls.sh left them on the volumes.
            val tlsDir = Files.createTempDirectory("kernfolio-init-test-openbao-tls")
            OpenBaoTls.copyOut(tlsDir) { path -> exec("base64", path) }
            openBao = OpenBaoContainer(INIT_TOKEN, tlsDir)
                .withNetwork(sharedNetwork)
                .withNetworkAliases("openbao")
            openBao.start()
        }

        private fun exec(vararg command: String): String {
            val result = init.execInContainer(*command)
            check(result.exitCode == 0) { "${command.joinToString(" ")} failed: ${result.stdout}\n${result.stderr}" }
            return result.stdout
        }

        // setup.sh waits for OpenBao forever; bound it so a broken setup fails.
        private fun runSetup() = exec("timeout", "120", "/openbao/init/setup.sh")

        /** A later `docker compose up`: no BAO_INIT_TOKEN. */
        private fun runSetupWithoutInitToken() = exec("sh", "-c", "BAO_INIT_TOKEN= timeout 120 /openbao/init/setup.sh")

        /** `bao <args>` with the token in [tokenFile]; returns the exit code and output. */
        private fun baoAs(tokenFile: String, vararg args: String): Pair<Int, String> {
            val result = init.execInContainer("sh", "-c", "BAO_TOKEN=\"\$(cat $tokenFile)\" bao \"\$@\"", "bao", *args)
            return result.exitCode to (result.stdout + result.stderr).trim()
        }

        /**
         * A field of the token's own data. Not `-field=`: for `renewable` that
         * reads the lookup response's flag, which is always false.
         */
        private fun lookup(tokenFile: String, field: String): String = exec(
            "sh", "-c",
            "BAO_TOKEN=\"\$(cat $tokenFile)\" bao token lookup -format=json | jq -r '.data.$field | if type == \"array\" then join(\",\") else tostring end'",
        ).trim()

        /** "dir/stem.pem" and "dir/stem-key.pem" for each stem. */
        private fun withKeys(vararg stems: String) = stems.flatMap { listOf("$it.pem", "$it-key.pem") }.toSet()

        /** "dir/file" -> md5 of every PKI service cert and key. */
        private fun allCerts(): Map<String, String> =
            exec("sh", "-c", "cd /openbao/certs && md5sum web/app*.pem caddy/*.pem optimizer/*.pem postgres/*.pem openbao/postgres-admin-client*.pem")
                .lines().filter { it.isNotBlank() }
                .associate { it.substringAfter("  ") to it.substringBefore("  ") }

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
        val listenerCertBefore = exec("cat", "/openbao/certs/openbao/openbao.pem")
        val passwordBefore = exec("cat", "/openbao/certs/openbao/pg_root_password")
        assertThat(exec("cat", "/openbao/certs/postgres/pg_root_password")).isEqualTo(passwordBefore)

        // A rerun has to replace files that now belong to other users, with no
        // DAC_OVERRIDE. It must also keep the CA and the postgres password.
        val webToken = exec("cat", WEB_TOKEN)
        val renewerToken = exec("cat", RENEWER_TOKEN)
        val output = runSetup()
        assertThat(output).contains(
            "Reusing existing root CA", "Reusing existing pg_root_password",
            "Reusing the kernfolio-app token", "Reusing the kernfolio-cert-renewer token",
        )
        assertThat(exec("cat", WEB_TOKEN)).isEqualTo(webToken)
        assertThat(exec("cat", RENEWER_TOKEN)).isEqualTo(renewerToken)
        assertThat(ownership()).isEqualTo(expected)
        assertThat(exec("cat", "/openbao/certs/web/ca.pem")).isEqualTo(caBefore)
        // setup.sh leaves setup-tls.sh's files alone.
        assertThat(exec("cat", "/openbao/certs/openbao/openbao.pem")).isEqualTo(listenerCertBefore)
        assertThat(exec("cat", "/openbao/certs/openbao/pg_root_password")).isEqualTo(passwordBefore)
        assertThat(exec("cat", "/openbao/certs/postgres/pg_root_password")).isEqualTo(passwordBefore)
    }

    @Test
    fun `bootstrap mints periodic orphan tokens with only their own policy`() {
        runSetup()
        for ((file, policy) in listOf(WEB_TOKEN to "kernfolio-app", RENEWER_TOKEN to "kernfolio-cert-renewer")) {
            assertThat(lookup(file, "policies")).describedAs(file).isEqualTo("default,$policy")
            assertThat(lookup(file, "orphan")).describedAs(file).isEqualTo("true")
            // Periodic through its role: lookup shows no `period`, but the
            // role, a 30-day TTL and no max TTL, so renewals can go on forever.
            assertThat(lookup(file, "role")).describedAs(file).isEqualTo(policy)
            assertThat(lookup(file, "creation_ttl")).describedAs(file).isEqualTo("2592000")
            assertThat(lookup(file, "explicit_max_ttl")).describedAs(file).isEqualTo("0")
            assertThat(lookup(file, "renewable")).describedAs(file).isEqualTo("true")
        }
        // The renewer's token issues certs and can do nothing else.
        assertThat(baoAs(RENEWER_TOKEN, "write", "-field=serial_number", "pki_int/issue/kernfolio-service", "common_name=caddy").first).isZero()
        assertThat(baoAs(RENEWER_TOKEN, "kv", "get", "secret/kernfolio").second).contains("Code: 403")
        assertThat(baoAs(RENEWER_TOKEN, "secrets", "list").second).contains("Code: 403")
        // Web's reads its secrets, but not the postgres superuser password.
        assertThat(baoAs(WEB_TOKEN, "kv", "get", "-field=admin.email", "secret/kernfolio")).isEqualTo(0 to "admin@kernfolio.dev")
        assertThat(baoAs(WEB_TOKEN, "kv", "get", "secret/kernfolio/pg-root").second).contains("Code: 403")
        assertThat(baoAs(WEB_TOKEN, "write", "-field=serial_number", "pki_int/issue/kernfolio-service", "common_name=caddy").second)
            .contains("Code: 403")
    }

    @Test
    fun `without BAO_INIT_TOKEN a rerun only issues certs, with the renewer's token`() {
        runSetup()
        val webToken = exec("cat", WEB_TOKEN)
        val renewerToken = exec("cat", RENEWER_TOKEN)
        exec("rm", "/openbao/certs/caddy/caddy-client-key.pem")
        val others = allCerts()

        val output = runSetupWithoutInitToken()

        assertThat(output).contains("only issuing certificates", "Issuing caddy-client.pem").doesNotContain("Minting")
        assertThat(output.lines().filter { "Issuing" in it }).hasSize(1)
        assertThat(ownership()).isEqualTo(expected)
        assertThat(exec("cat", WEB_TOKEN)).isEqualTo(webToken)
        assertThat(exec("cat", RENEWER_TOKEN)).isEqualTo(renewerToken)
        val caddy = withKeys("caddy/caddy-client")
        assertThat(allCerts() - caddy).isEqualTo(others - caddy)
        exec("openssl", "verify", "-CAfile", "/openbao/certs/caddy/ca.pem", "-untrusted", "/openbao/certs/caddy/caddy-client.pem",
            "/openbao/certs/caddy/caddy-client.pem")
    }

    @Test
    fun `a rerun issues only the certs that are due, missing, mismatched, misnamed or from another CA`() {
        runSetup()
        assertThat(runSetup()).doesNotContain("Issuing")

        // Due: OpenBao backdates certs by 30 s, so a 12-second one has 12 of 42 s
        // left, less than a third.
        exec("sh", "-c", "BAO_TOKEN=$INIT_TOKEN LEAF_TTL=12s sh -c '. /openbao/init/common.sh && . /openbao/init/pki-certs.sh && " +
            "rm /openbao/certs/optimizer/optimizer.pem && issue_if_due /openbao/certs/optimizer optimizer localhost optimizer 65532'")
        // Mismatched: app-client's key, under app.pem. (Root may replace web's
        // 0600 key, not write into it.)
        exec("sh", "-c", "cd /openbao/certs/web && cp app-client-key.pem k.tmp && mv -f k.tmp app-key.pem")
        // Misnamed: postgres's server cert without its `localhost` SAN.
        exec("sh", "-c", "BAO_TOKEN=$INIT_TOKEN sh -c '. /openbao/init/common.sh && . /openbao/init/pki-certs.sh && " +
            "rm /openbao/certs/postgres/postgres.pem && issue_if_due /openbao/certs/postgres postgres \"\" postgres 70'")
        // Another CA: a self-signed caddy-client.pem.
        exec("sh", "-c", "cd /openbao/certs/caddy && rm caddy-client*.pem && openssl req -x509 -newkey ed25519 -nodes -subj /CN=caddy -days 30 " +
            "-keyout caddy-client-key.pem -out caddy-client.pem 2>/dev/null")
        val before = allCerts()

        val issued = runSetup().lines().filter { "Issuing" in it }

        assertThat(issued).containsExactlyInAnyOrder(
            "=== Issuing optimizer.pem (CN optimizer) ===",
            "=== Issuing app.pem (CN app) ===",
            "=== Issuing postgres.pem (CN postgres) ===",
            "=== Issuing caddy-client.pem (CN caddy) ===",
        )
        val changed = withKeys("optimizer/optimizer", "web/app", "postgres/postgres", "caddy/caddy-client")
        assertThat(allCerts() - changed).isEqualTo(before - changed)
        assertThat(exec("openssl", "x509", "-in", "/openbao/certs/postgres/postgres.pem", "-noout", "-ext", "subjectAltName"))
            .contains("DNS:localhost", "DNS:postgres")
        assertThat(ownership()).isEqualTo(expected)
        assertThat(runSetup()).doesNotContain("Issuing")
    }

    @Test
    fun `without BAO_INIT_TOKEN and a working renewer token it fails, pointing at the README`() {
        runSetup()
        exec("mv", RENEWER_TOKEN, "$RENEWER_TOKEN.saved")
        try {
            val result = init.execInContainer("sh", "-c", "BAO_INIT_TOKEN= timeout 120 /openbao/init/setup.sh")
            assertThat(result.exitCode).isNotZero()
            assertThat(result.stderr).contains("OpenBao isn't bootstrapped", "README")
        } finally {
            exec("mv", "$RENEWER_TOKEN.saved", RENEWER_TOKEN)
        }
    }

    @Test
    fun `bootstrap reruns keep the operator's KV values and re-mint a revoked token`() {
        runSetup()
        exec("sh", "-c", "BAO_TOKEN=$INIT_TOKEN bao kv patch secret/kernfolio admin.email=ops@example.com")
        val (code, out) = baoAs(WEB_TOKEN, "token", "revoke", "-self")
        check(code == 0) { out }

        assertThat(runSetup()).contains("Keeping existing secret/kernfolio", "Minting the kernfolio-app token")
        assertThat(baoAs(WEB_TOKEN, "kv", "get", "-field=admin.email", "secret/kernfolio")).isEqualTo(0 to "ops@example.com")
        exec("sh", "-c", "BAO_TOKEN=$INIT_TOKEN bao kv patch secret/kernfolio admin.email=admin@kernfolio.dev")
    }
}
