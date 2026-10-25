package com.kernfolio

import com.github.dockerjava.api.model.Capability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer
import java.nio.file.Files

/**
 * openbao/init/setup-tls.sh, run as compose's openbao-tls-init and (with
 * `--leaves-only`) by cert-renewer: real image, root with only CHOWN +
 * DAC_READ_SEARCH, no-new-privileges, on empty volumes. It makes OpenBao's
 * listener CA once, keeps its key, and renews each leaf on its own when it's
 * due. That the certs actually work (server, `bao` CLI, Spring) is in
 * [OpenBaoInitIntegrationTest] and [OpenBaoIntegrationTest].
 */
class OpenBaoTlsInitIntegrationTest {

    companion object {
        private const val OPENBAO = "/openbao/certs/openbao"
        private const val WEB = "/openbao/certs/web"
        private const val TLS_CA = "/openbao/tls-ca"
        private const val ALL = "$OPENBAO/* $WEB/* $TLS_CA/*"
        private const val NEW_CA = "Generating OpenBao's TLS CA"
        private const val ISSUED = "Issuing OpenBao TLS cert"
        private val LEAVES = listOf("$OPENBAO/openbao.pem", "$OPENBAO/openbao-client.pem", "$WEB/openbao-client.pem")

        private val init = GenericContainer(OpenBaoTls.initImage).apply {
            withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/common.sh"), "/openbao/init/common.sh")
            withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/setup-tls.sh", 0b111_101_101), "/openbao/init/setup-tls.sh")
            withTmpFs(mapOf(OPENBAO to "rw,mode=755", WEB to "rw,mode=755", TLS_CA to "rw,mode=755"))
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

        private fun exec(script: String) = init.execInContainer("sh", "-c", script)

        private fun sh(script: String): String {
            val result = exec(script)
            check(result.exitCode == 0) { "$script failed: ${result.stdout}\n${result.stderr}" }
            return result.stdout
        }

        private fun runSetupTls(args: String = "") = sh("/openbao/init/setup-tls.sh $args")

        private fun freshRun(env: String = ""): String {
            sh("rm -f $ALL")
            return sh("$env /openbao/init/setup-tls.sh")
        }

        private fun md5(files: String) = sh("cat $files | md5sum")

        private fun files(dir: String): Map<String, String> =
            sh("cd $dir && stat -c '%n %u:%g %a' *").lines().filter { it.isNotBlank() }
                .associate { it.substringBefore(' ') to it.substringAfter(' ') }

        private fun x509(file: String) = sh("openssl x509 -in $file -noout -text")

        private fun verify(file: String) = assertThat(sh("openssl verify -CAfile $OPENBAO/openbao-ca.pem $file")).contains("OK")

        /**
         * Re-signs web's client cert with the CA so that it started 80 days
         * ago and ends in 5: less than a third of its lifetime left, so due.
         */
        private fun makeWebCertDue() {
            // In the script, $($date<epoch>) is that time in the format openssl wants.
            val date = "date -u +%Y%m%d%H%M%SZ -d @"
            sh(
                """
                cd /tmp && openssl req -new -key $WEB/openbao-client-key.pem -subj /CN=app -out web.csr &&
                openssl x509 -req -in web.csr -CA $OPENBAO/openbao-ca.pem -CAkey $TLS_CA/openbao-ca-key.pem \
                  -not_before $($date$(( $(date +%s) - 80 * 86400 ))) -not_after $($date$(( $(date +%s) + 5 * 86400 ))) \
                  -out web.pem 2>/dev/null &&
                cat web.pem $OPENBAO/openbao-ca.pem > $WEB/openbao-client.pem
                """.trimIndent()
            )
        }
    }

    @Test
    fun `makes the CA and leaf certs, owned as compose needs, and keeps them on a rerun`() {
        assertThat(freshRun()).contains(NEW_CA, "$ISSUED $OPENBAO/openbao.pem", "$ISSUED $OPENBAO/openbao-client.pem", "$ISSUED $WEB/openbao-client.pem")

        assertThat(files(TLS_CA)).isEqualTo(mapOf("openbao-ca-key.pem" to "0:0 600", "lock" to "0:0 600"))
        assertThat(files(OPENBAO)).isEqualTo(
            mapOf(
                "openbao.pem" to "0:0 644", "openbao-key.pem" to "100:0 600",
                "openbao-client.pem" to "0:0 644", "openbao-client-key.pem" to "100:0 600",
                "openbao-ca.pem" to "0:0 644",
            )
        )
        assertThat(files(WEB)).isEqualTo(
            mapOf("openbao-client.pem" to "0:0 644", "openbao-client-key.pem" to "1002:0 600", "openbao-ca.pem" to "0:0 644")
        )
        assertThat(sh("cat $WEB/openbao-ca.pem")).isEqualTo(sh("cat $OPENBAO/openbao-ca.pem"))

        assertThat(x509("$OPENBAO/openbao-ca.pem")).contains("Subject: CN=Kernfolio OpenBao TLS CA", "CA:TRUE")
        // About 10 years: the anchor outlives many leaf renewals.
        sh("openssl x509 -in $OPENBAO/openbao-ca.pem -noout -checkend ${3600L * 86400}")
        assertThat(x509("$OPENBAO/openbao.pem")).contains(
            "Subject: CN=openbao", "NIST CURVE: P-256", "DNS:openbao, DNS:localhost, IP Address:127.0.0.1",
            "TLS Web Server Authentication", "CA:FALSE",
        )
        assertThat(x509("$OPENBAO/openbao-client.pem")).contains(
            "Subject: CN=openbao-cli", "NIST CURVE: P-256", "TLS Web Client Authentication", "CA:FALSE",
        ).doesNotContain("Server Authentication")
        assertThat(x509("$WEB/openbao-client.pem")).contains(
            "Subject: CN=app", "NIST CURVE: P-256", "TLS Web Client Authentication", "CA:FALSE",
        ).doesNotContain("Server Authentication")
        for (cert in LEAVES) {
            verify(cert)
            // 90 days.
            sh("openssl x509 -in $cert -noout -checkend ${89 * 86400}")
            assertThat(exec("openssl x509 -in $cert -noout -checkend ${91 * 86400}").exitCode).isEqualTo(1)
        }

        val before = md5(ALL)
        assertThat(runSetupTls()).doesNotContain(NEW_CA, ISSUED).contains("OpenBao TLS certificates are current")
        assertThat(runSetupTls("--leaves-only")).isBlank()
        assertThat(md5(ALL)).isEqualTo(before)
    }

    @Test
    fun `renews a due leaf alone, with the same CA`() {
        freshRun()
        makeWebCertDue()
        val ca = md5("$OPENBAO/openbao-ca.pem $WEB/openbao-ca.pem $TLS_CA/openbao-ca-key.pem")
        val others = md5("$OPENBAO/openbao.pem $OPENBAO/openbao-key.pem $OPENBAO/openbao-client.pem $OPENBAO/openbao-client-key.pem")
        val oldKey = md5("$WEB/openbao-client-key.pem")

        val out = runSetupTls("--leaves-only")

        assertThat(out).contains("$ISSUED $WEB/openbao-client.pem (CN app)").doesNotContain(NEW_CA, "$ISSUED $OPENBAO")
        assertThat(md5("$OPENBAO/openbao-ca.pem $WEB/openbao-ca.pem $TLS_CA/openbao-ca-key.pem")).isEqualTo(ca)
        assertThat(md5("$OPENBAO/openbao.pem $OPENBAO/openbao-key.pem $OPENBAO/openbao-client.pem $OPENBAO/openbao-client-key.pem"))
            .isEqualTo(others)
        assertThat(md5("$WEB/openbao-client-key.pem")).isNotEqualTo(oldKey)
        verify("$WEB/openbao-client.pem")
        sh("openssl x509 -in $WEB/openbao-client.pem -noout -checkend ${89 * 86400}")
        assertThat(files(WEB)["openbao-client-key.pem"]).isEqualTo("1002:0 600")
    }

    @Test
    fun `keeps a short-lived leaf while more than a third of its lifetime is left`() {
        // 5 minutes: renewal would only start with 100 s left.
        freshRun("OPENBAO_TLS_LEAF_SECONDS=300")
        val before = md5(ALL)
        assertThat(runSetupTls("--leaves-only")).isBlank()
        assertThat(md5(ALL)).isEqualTo(before)
    }

    @Test
    fun `replaces only a server cert with other SANs`() {
        freshRun()
        val clients = md5("$OPENBAO/openbao-client.pem $WEB/openbao-client.pem")
        sh("""sed 's/^SAN=.*/SAN="DNS:openbao"/; s/^SAN_AS_PRINTED=.*/SAN_AS_PRINTED="DNS:openbao"/' /openbao/init/setup-tls.sh > /openbao/init/variant.sh""")
        assertThat(sh("sh /openbao/init/variant.sh")).contains("$ISSUED $OPENBAO/openbao.pem")
        assertThat(sh("openssl x509 -in $OPENBAO/openbao.pem -noout -ext subjectAltName")).contains("DNS:openbao").doesNotContain("localhost")

        assertThat(runSetupTls("--leaves-only")).contains("$ISSUED $OPENBAO/openbao.pem").doesNotContain("openbao-client")
        assertThat(sh("openssl x509 -in $OPENBAO/openbao.pem -noout -ext subjectAltName"))
            .contains("DNS:openbao, DNS:localhost, IP Address:127.0.0.1")
        assertThat(md5("$OPENBAO/openbao-client.pem $WEB/openbao-client.pem")).isEqualTo(clients)
    }

    @Test
    fun `a recreated certs_web gets new client certs from the same CA`() {
        freshRun()
        val ca = md5("$OPENBAO/openbao-ca.pem")
        sh("rm $WEB/*")
        assertThat(runSetupTls()).contains("$ISSUED $WEB/openbao-client.pem").doesNotContain(NEW_CA, "$ISSUED $OPENBAO")
        assertThat(md5("$OPENBAO/openbao-ca.pem")).isEqualTo(ca)
        assertThat(md5("$WEB/openbao-ca.pem")).isEqualTo(ca)
        verify("$WEB/openbao-client.pem")
    }

    @Test
    fun `without the CA key, openbao-tls-init starts over and cert-renewer refuses to`() {
        freshRun()
        val before = md5(ALL.replace("$TLS_CA/*", ""))
        sh("rm $TLS_CA/openbao-ca-key.pem")

        val renewer = exec("/openbao/init/setup-tls.sh --leaves-only")
        assertThat(renewer.exitCode).isEqualTo(1)
        assertThat(renewer.stderr).contains("only", "openbao-tls-init may replace it", "docker compose down")
        assertThat(md5(ALL.replace("$TLS_CA/*", ""))).isEqualTo(before)

        // Like a stack from before the CA key was kept: a new CA and every leaf.
        val out = runSetupTls()
        assertThat(out).contains(NEW_CA)
        for (cert in LEAVES) {
            assertThat(out).contains("$ISSUED $cert")
            verify(cert)
        }
        assertThat(sh("cat $WEB/openbao-ca.pem")).isEqualTo(sh("cat $OPENBAO/openbao-ca.pem"))
    }

    @Test
    fun `removes web's old PKCS#12`() {
        freshRun()
        sh("touch $WEB/openbao-client.p12")
        runSetupTls()
        assertThat(files(WEB)).doesNotContainKey("openbao-client.p12")
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
    fun `web's client cert and key load as a Java key store`() {
        freshRun()
        val dir = Files.createTempDirectory("openbao-tls-init-test")
        OpenBaoTls.copyOut(dir) { path -> sh("base64 $path") }
        val keyStore = OpenBaoTls.webKeyStore(dir)
        assertThat(keyStore.getKey("client", CharArray(0)).algorithm).isEqualTo("EC")
        // Leaf + CA, so the server can build the chain.
        assertThat(keyStore.getCertificateChain("client")).hasSize(2)
    }
}
