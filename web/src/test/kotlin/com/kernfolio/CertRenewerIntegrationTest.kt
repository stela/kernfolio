package com.kernfolio

import com.github.dockerjava.api.model.AccessMode
import com.github.dockerjava.api.model.Bind
import com.github.dockerjava.api.model.Capability
import com.github.dockerjava.api.model.Volume
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.util.UUID

/**
 * openbao/init/renew.sh, cert-renewer's rounds, against a real OpenBao
 * bootstrapped by setup.sh and a real postgres started like compose's, on
 * docker volumes like compose's: postgres reads its cert from the volume the
 * renewer writes to. Each round is `renew.sh --once`. Which certs are due is
 * [OpenBaoInitIntegrationTest]'s and [OpenBaoTlsInitIntegrationTest]'s
 * business; here it's that a round renews them, postgres reloads, and the
 * tokens are renewed.
 */
private const val INIT_TOKEN = "renewer-test-token"

class CertRenewerIntegrationTest {

    companion object {
        private val sharedNetwork = Network.newNetwork()
        private val docker = DockerClientFactory.instance().client()
        private val prefix = "kernfolio-renewer-test-${UUID.randomUUID().toString().take(8)}"

        /** Volume -> where cert-renewer mounts it, as in docker-compose.yml. */
        private val mounts = listOf("web", "caddy", "optimizer", "postgres", "openbao", "renewer")
            .associate { "$prefix-certs-$it" to "/openbao/certs/$it" } + ("$prefix-tls-ca" to "/openbao/tls-ca")

        private const val POSTGRES_PEM = "/openbao/certs/postgres/postgres.pem"
        private const val WEB_TOKEN = "/openbao/certs/web/openbao-token"
        private const val RENEWER_TOKEN = "/openbao/certs/renewer/openbao-token"

        private val renewer = GenericContainer(OpenBaoTls.initImage).apply {
            withNetwork(sharedNetwork)
            withEnv("BAO_ADDR", "https://openbao:8200")
            withEnv("BAO_CACERT", "/openbao/certs/openbao/openbao-ca.pem")
            withEnv("BAO_CLIENT_CERT", "/openbao/certs/openbao/openbao-client.pem")
            withEnv("BAO_CLIENT_KEY", "/openbao/certs/openbao/openbao-client-key.pem")
            OpenBaoTls.copyInitScripts(this)
            withCommand("tail", "-f", "/dev/null")
            withCreateContainerCmdModifier { cmd ->
                cmd.withUser("0:0")
                cmd.hostConfig!!
                    .withBinds(mounts.map { (volume, path) -> Bind(volume, Volume(path)) })
                    .withCapDrop(Capability.ALL)
                    .withCapAdd(Capability.CHOWN, Capability.DAC_READ_SEARCH)
                    .withSecurityOpts(listOf("no-new-privileges:true"))
            }
        }

        private lateinit var openBao: OpenBaoContainer

        private val postgres = GenericContainer(DockerImageName.parse("postgres:18-alpine")).apply {
            withNetwork(sharedNetwork)
            withNetworkAliases("postgres")
            withEnv("POSTGRES_DB", "kernfolio")
            withEnv("POSTGRES_USER", "kernfolio")
            withEnv("POSTGRES_PASSWORD_FILE", "/openbao/certs/pg_root_password")
            withEnv("PGDATA", "/var/lib/postgresql/data")
            withCopyFileToContainer(KernfolioPostgresContainer.repoFile("postgres/docker-entrypoint-tls.sh", 0b111_101_101), "/usr/local/bin/docker-entrypoint-tls.sh")
            withCopyFileToContainer(KernfolioPostgresContainer.repoFile("postgres/pg_hba_mtls.conf"), "/etc/postgresql/pg_hba.conf")
            withCreateContainerCmdModifier {
                it.withEntrypoint("/usr/local/bin/docker-entrypoint-tls.sh")
                it.hostConfig!!.withBinds(Bind("$prefix-certs-postgres", Volume("/openbao/certs"), AccessMode.ro))
            }
            waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
        }

        @JvmStatic
        @BeforeAll
        fun start() {
            renewer.start()
            exec("/openbao/init/setup-tls.sh")
            val tlsDir = Files.createTempDirectory("kernfolio-renewer-test-openbao-tls")
            OpenBaoTls.copyOut(tlsDir) { path -> exec("base64", path) }
            openBao = OpenBaoContainer(INIT_TOKEN, tlsDir).withNetwork(sharedNetwork).withNetworkAliases("openbao")
            openBao.start()
            exec("sh", "-c", "BAO_INIT_TOKEN=$INIT_TOKEN timeout 120 /openbao/init/setup.sh")
            postgres.start()
        }

        @JvmStatic
        @AfterAll
        fun stop() {
            postgres.stop()
            openBao.stop()
            renewer.stop()
            for (volume in mounts.keys) docker.removeVolumeCmd(volume).exec()
        }

        private fun exec(vararg command: String): String {
            val result = renewer.execInContainer(*command)
            check(result.exitCode == 0) { "${command.joinToString(" ")} failed: ${result.stdout}\n${result.stderr}" }
            return result.stdout
        }

        private fun round(env: String = "") = exec("sh", "-c", "$env timeout 120 /openbao/init/renew.sh --once")

        private fun fileSerial(cert: String) = exec("openssl", "x509", "-in", cert, "-noout", "-serial").trim()

        /** The serial of the cert postgres presents to a new connection. */
        private fun servedSerial() = exec(
            "sh", "-c",
            "openssl s_client -starttls postgres -connect postgres:5432 </dev/null 2>/dev/null | openssl x509 -noout -serial",
        ).trim()

        private fun startedAt(): String = docker.inspectContainerCmd(postgres.containerId).exec().state.startedAt!!

        /** md5 of every PKI cert and key except postgres's. */
        private fun otherCerts() = exec(
            "sh", "-c",
            "cd /openbao/certs && md5sum web/app*.pem caddy/caddy-client*.pem optimizer/optimizer*.pem openbao/postgres-admin-client*.pem",
        )

        private fun ttl(tokenFile: String) =
            exec("sh", "-c", "BAO_TOKEN=\"\$(cat $tokenFile)\" bao token lookup -format=json | jq .data.ttl").trim().toLong()
    }

    @Test
    fun `a round renews a due cert alone, and postgres serves it without a restart`() {
        // A 12 s cert: OpenBao backdates by 30 s, so it's due right away.
        exec("rm", POSTGRES_PEM)
        assertThat(round("LEAF_TTL=12s")).contains("Issuing postgres.pem", "postgres reloaded its certificate")
        val short = fileSerial(POSTGRES_PEM)
        assertThat(servedSerial()).isEqualTo(short)
        val started = startedAt()
        val others = otherCerts()

        val out = round()

        assertThat(out.lines().filter { "Issuing" in it }).containsExactly("=== Issuing postgres.pem (CN postgres) ===")
        assertThat(out).contains("postgres reloaded its certificate")
        assertThat(fileSerial(POSTGRES_PEM)).isNotEqualTo(short)
        assertThat(servedSerial()).isEqualTo(fileSerial(POSTGRES_PEM))
        assertThat(otherCerts()).isEqualTo(others)
        assertThat(startedAt()).isEqualTo(started)

        // Nothing due: nothing issued, and postgres isn't reloaded again.
        assertThat(round()).doesNotContain("Issuing", "reloaded")
    }

    @Test
    fun `a round recovers a missing OpenBao CLI cert before it needs OpenBao`() {
        exec("rm", "/openbao/certs/openbao/openbao-client.pem")
        assertThat(round()).contains("Issuing OpenBao TLS cert /openbao/certs/openbao/openbao-client.pem")
        assertThat(round()).doesNotContain("Issuing")
    }

    @Test
    fun `a round renews both scoped tokens`() {
        round()
        Thread.sleep(3_000)
        val web = ttl(WEB_TOKEN)
        val renewer = ttl(RENEWER_TOKEN)

        round()

        assertThat(ttl(WEB_TOKEN)).isGreaterThan(web)
        assertThat(ttl(RENEWER_TOKEN)).isGreaterThan(renewer)
    }

    @Test
    fun `a round without a working token fails, pointing at the README`() {
        exec("mv", RENEWER_TOKEN, "$RENEWER_TOKEN.saved")
        try {
            val result = renewer.execInContainer("timeout", "120", "/openbao/init/renew.sh", "--once")
            assertThat(result.exitCode).isEqualTo(1)
            assertThat(result.stderr).contains("cert-renewer's OpenBao token doesn't work", "README")
        } finally {
            exec("mv", "$RENEWER_TOKEN.saved", RENEWER_TOKEN)
        }
    }

    @Test
    fun `the loop stops promptly on TERM, as docker compose stop sends it`() {
        val out = exec(
            "sh", "-c",
            "/openbao/init/renew.sh > /tmp/loop.log 2>&1 & pid=\$!; sleep 5; start=\$(date +%s); kill -TERM \$pid; " +
                "wait \$pid; echo \"rc=\$? took=\$((\$(date +%s) - start))\"",
        )
        assertThat(out).contains("rc=0")
        assertThat(out.substringAfter("took=").trim().toInt()).isLessThan(3)
        assertThat(exec("cat", "/tmp/loop.log")).contains("a round every 3600s")
    }
}
