package com.kernfolio

import com.github.dockerjava.api.model.Capability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import java.nio.file.Files

/**
 * openbao/init/setup-tls.sh, run as compose's openbao-tls-init: real image, root
 * with only CHOWN + DAC_READ_SEARCH, no-new-privileges, on empty volumes. It
 * makes the certs for OpenBao's mutual-TLS listener and keeps them across
 * runs unless one is about to expire, is missing, or the server cert has the
 * wrong SANs. That they actually work (server, `bao` CLI, Spring) is in
 * [OpenBaoInitIntegrationTest] and [OpenBaoIntegrationTest].
 */
class OpenBaoTlsInitIntegrationTest {

    companion object {
        private const val OPENBAO = "/openbao/certs/openbao"
        private const val WEB = "/openbao/certs/web"
        private const val ALL = "$OPENBAO/* $WEB/*"
        private const val GENERATED = "Generating OpenBao TLS certificates"
        private const val REUSED = "Reusing existing OpenBao TLS certificates"

        private val init = GenericContainer(OpenBaoTls.initImage).apply {
            withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/common.sh"), "/openbao/init/common.sh")
            withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/setup-tls.sh", 0b111_101_101), "/openbao/init/setup-tls.sh")
            withTmpFs(mapOf(OPENBAO to "rw,mode=755", WEB to "rw,mode=755"))
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
        fun start() = init.start()

        @JvmStatic
        @AfterAll
        fun stop() = init.stop()

        private fun sh(script: String): String {
            val result = init.execInContainer("sh", "-c", script)
            check(result.exitCode == 0) { "$script failed: ${result.stdout}\n${result.stderr}" }
            return result.stdout
        }

        private fun runSetupTls() = sh("/openbao/init/setup-tls.sh")

        private fun freshRun(): String {
            sh("rm -f $ALL")
            return runSetupTls()
        }

        /** Runs setup-tls.sh with [sed] applied to it, from empty volumes. */
        private fun runVariant(sed: String): String {
            sh("rm -f $ALL && sed '$sed' /openbao/init/setup-tls.sh > /openbao/init/variant.sh && sh /openbao/init/variant.sh")
            return sh("cat $ALL | md5sum")
        }

        private fun files(dir: String): Map<String, String> =
            sh("cd $dir && stat -c '%n %u:%g %a' *").lines().filter { it.isNotBlank() }
                .associate { it.substringBefore(' ') to it.substringAfter(' ') }

        private fun x509(file: String) = sh("openssl x509 -in $file -noout -text")

        private fun verify(file: String) = assertThat(sh("openssl verify -CAfile $OPENBAO/openbao-ca.pem $file")).contains("OK")

        /** Web's client cert, out of its PKCS#12, as a PEM file path. */
        private fun webClientCert(): String {
            sh("openssl pkcs12 -in $WEB/openbao-client.p12 -passin pass: -nokeys -clcerts -out /tmp/web.pem")
            return "/tmp/web.pem"
        }
    }

    @Test
    fun `makes the server and client certs, owned as compose needs, and keeps them on a rerun`() {
        assertThat(freshRun()).contains(GENERATED)

        assertThat(files(OPENBAO)).isEqualTo(
            mapOf(
                "openbao.pem" to "0:0 644", "openbao-key.pem" to "100:0 600",
                "openbao-client.pem" to "0:0 644", "openbao-client-key.pem" to "100:0 600",
                "openbao-ca.pem" to "0:0 644",
            )
        )
        assertThat(files(WEB)).isEqualTo(mapOf("openbao-client.p12" to "1002:0 600", "openbao-ca.pem" to "0:0 644"))
        assertThat(sh("cat $WEB/openbao-ca.pem")).isEqualTo(sh("cat $OPENBAO/openbao-ca.pem"))

        assertThat(x509("$OPENBAO/openbao.pem")).contains(
            "Subject: CN=openbao", "NIST CURVE: P-256", "DNS:openbao, DNS:localhost, IP Address:127.0.0.1",
            "TLS Web Server Authentication", "CA:FALSE",
        )
        assertThat(x509("$OPENBAO/openbao-client.pem")).contains(
            "Subject: CN=openbao-cli", "NIST CURVE: P-256", "TLS Web Client Authentication", "CA:FALSE",
        ).doesNotContain("Server Authentication")
        assertThat(x509(webClientCert())).contains(
            "Subject: CN=app", "NIST CURVE: P-256", "TLS Web Client Authentication", "CA:FALSE",
        ).doesNotContain("Server Authentication")
        for (cert in listOf("$OPENBAO/openbao.pem", "$OPENBAO/openbao-client.pem", webClientCert())) {
            verify(cert)
            // About two years, so they aren't replaced on every start.
            sh("openssl x509 -in $cert -noout -checkend ${700 * 86400}")
        }

        val before = sh("cat $ALL | md5sum")
        assertThat(runSetupTls()).contains(REUSED)
        assertThat(sh("cat $ALL | md5sum")).isEqualTo(before)
    }

    @Test
    fun `writes dev_hcl's unseal key only when asked, and never replaces it`() {
        freshRun()
        assertThat(files(OPENBAO)).doesNotContainKey("dev-unseal.key")

        val key = "$OPENBAO/dev-unseal.key"
        assertThat(sh("OPENBAO_DEV_UNSEAL_KEY=1 /openbao/init/setup-tls.sh")).contains("Generating the dev unseal key")
        assertThat(files(OPENBAO)["dev-unseal.key"]).isEqualTo("100:0 600")
        assertThat(sh("wc -c < $key").trim()).isEqualTo("32")

        val before = sh("md5sum $key")
        assertThat(sh("OPENBAO_DEV_UNSEAL_KEY=1 /openbao/init/setup-tls.sh")).doesNotContain("dev unseal key")
        assertThat(sh("md5sum $key")).isEqualTo(before)
    }

    @Test
    fun `web's PKCS#12 loads the way Spring Vault loads it, with no password`() {
        freshRun()
        val p12 = Files.createTempDirectory("openbao-tls-init-test")
        OpenBaoTls.copyOut(p12) { path -> sh("base64 $path") }
        val keyStore = OpenBaoTls.webKeyStore(p12)
        assertThat(keyStore.isKeyEntry("openbao-client")).isTrue()
        assertThat(keyStore.getKey("openbao-client", CharArray(0)).algorithm).isEqualTo("EC")
        // Leaf + bootstrap CA, so the server can build the chain.
        assertThat(keyStore.getCertificateChain("openbao-client")).hasSize(2)
    }

    @Test
    fun `replaces certs that expire within 30 days, not ones that don't`() {
        val shortLived = runVariant("s/^VALID_DAYS=730$/VALID_DAYS=10/")
        assertThat(runSetupTls()).contains(GENERATED)
        assertThat(sh("cat $ALL | md5sum")).isNotEqualTo(shortLived)

        // The same variant with a lifetime above the 30 days is kept, so it's
        // the expiry that triggers the replacement above.
        val longerLived = runVariant("s/^VALID_DAYS=730$/VALID_DAYS=60/")
        assertThat(runSetupTls()).contains(REUSED)
        assertThat(sh("cat $ALL | md5sum")).isEqualTo(longerLived)
    }

    @Test
    fun `replaces a server cert with other SANs`() {
        runVariant("""s/^SAN=.*/SAN="DNS:openbao"/; s/^SAN_AS_PRINTED=.*/SAN_AS_PRINTED="DNS:openbao"/""")
        assertThat(runSetupTls()).contains(GENERATED)
        assertThat(sh("openssl x509 -in $OPENBAO/openbao.pem -noout -ext subjectAltName"))
            .contains("DNS:openbao, DNS:localhost, IP Address:127.0.0.1")
    }

    @Test
    fun `starts over when any file is missing, e g a recreated certs_web`() {
        freshRun()
        val ca = sh("cat $OPENBAO/openbao-ca.pem")
        sh("rm $WEB/openbao-client.p12")
        assertThat(runSetupTls()).contains(GENERATED)
        // A new CA: the old one's key is gone, so nothing could be issued from it.
        assertThat(sh("cat $OPENBAO/openbao-ca.pem")).isNotEqualTo(ca)
        verify(webClientCert())
    }
}
