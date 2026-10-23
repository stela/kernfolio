package com.kernfolio

import com.github.dockerjava.api.model.Capability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.GenericContainer

/**
 * openbao/init/setup-tls.sh, run as compose's openbao-tls-init: real image, root
 * with only CHOWN + DAC_READ_SEARCH, no-new-privileges, on empty volumes. It
 * makes OpenBao's listener cert and keeps it across runs unless it is about to
 * expire or has the wrong SANs. That the cert actually serves and that
 * setup.sh trusts it is in [OpenBaoInitIntegrationTest].
 */
class OpenBaoTlsInitIntegrationTest {

    companion object {
        private const val OPENBAO = "/openbao/certs/openbao"
        private const val WEB = "/openbao/certs/web"

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

        private fun clearVolumes() = sh("rm -f $OPENBAO/* $WEB/*")

        private fun files(dir: String): Map<String, String> =
            sh("cd $dir && stat -c '%n %u:%g %a' *").lines().filter { it.isNotBlank() }
                .associate { it.substringBefore(' ') to it.substringAfter(' ') }

        /**
         * Puts a CA + leaf chain on the volumes, as an earlier run would have,
         * but valid for [days] and with SANs [san].
         */
        private fun plantCert(days: Int, san: String) {
            clearVolumes()
            sh(
                """
                set -e
                cd "$(mktemp -d)"
                openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout ca-key.pem -out ca.pem \
                  -days $days -subj "/CN=planted CA" 2>/dev/null
                openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout key.pem -out leaf.csr \
                  -subj "/CN=openbao" 2>/dev/null
                printf 'subjectAltName=$san\n' > ext
                openssl x509 -req -in leaf.csr -CA ca.pem -CAkey ca-key.pem -set_serial 1 -days $days \
                  -extfile ext -out leaf.pem 2>/dev/null
                cat leaf.pem ca.pem > $OPENBAO/openbao.pem
                cp key.pem $OPENBAO/openbao-key.pem
                cp ca.pem $OPENBAO/openbao-ca.pem
                """.trimIndent()
            )
        }
    }

    @Test
    fun `makes a P-256 cert for openbao, owned as compose needs, and keeps it on a rerun`() {
        clearVolumes()
        assertThat(runSetupTls()).contains("Generating OpenBao listener certificate")

        assertThat(files(OPENBAO)).isEqualTo(
            mapOf("openbao.pem" to "0:0 644", "openbao-key.pem" to "100:0 600", "openbao-ca.pem" to "0:0 644")
        )
        assertThat(files(WEB)).isEqualTo(mapOf("openbao-ca.pem" to "0:0 644"))
        assertThat(sh("cat $WEB/openbao-ca.pem")).isEqualTo(sh("cat $OPENBAO/openbao-ca.pem"))

        val cert = sh("openssl x509 -in $OPENBAO/openbao.pem -noout -text")
        assertThat(cert).contains(
            "Subject: CN=openbao",
            "NIST CURVE: P-256",
            "DNS:openbao, DNS:localhost, IP Address:127.0.0.1",
            "TLS Web Server Authentication",
            "CA:FALSE",
        )
        assertThat(sh("openssl verify -CAfile $OPENBAO/openbao-ca.pem $OPENBAO/openbao.pem")).contains("OK")
        // About two years, so it isn't replaced on every start.
        sh("openssl x509 -in $OPENBAO/openbao.pem -noout -checkend ${700 * 86400}")

        val before = sh("cat $OPENBAO/openbao.pem $OPENBAO/openbao-key.pem $OPENBAO/openbao-ca.pem")
        sh("rm $WEB/openbao-ca.pem")
        assertThat(runSetupTls()).contains("Reusing existing OpenBao listener certificate")
        assertThat(sh("cat $OPENBAO/openbao.pem $OPENBAO/openbao-key.pem $OPENBAO/openbao-ca.pem")).isEqualTo(before)
        // A recreated certs_web gets the anchor back.
        assertThat(sh("cat $WEB/openbao-ca.pem")).isEqualTo(sh("cat $OPENBAO/openbao-ca.pem"))
    }

    @Test
    fun `replaces a cert that expires within 30 days`() {
        plantCert(days = 10, san = "DNS:openbao,DNS:localhost,IP:127.0.0.1")
        assertThat(runSetupTls()).contains("Generating OpenBao listener certificate")
        sh("openssl x509 -in $OPENBAO/openbao.pem -noout -checkend ${700 * 86400}")
        assertThat(sh("openssl x509 -in $OPENBAO/openbao-ca.pem -noout -subject")).contains("Kernfolio OpenBao Bootstrap CA")
    }

    @Test
    fun `replaces a cert with other SANs`() {
        plantCert(days = 365, san = "DNS:openbao")
        assertThat(runSetupTls()).contains("Generating OpenBao listener certificate")
        assertThat(sh("openssl x509 -in $OPENBAO/openbao.pem -noout -ext subjectAltName"))
            .contains("DNS:openbao, DNS:localhost, IP Address:127.0.0.1")
    }

    @Test
    fun `keeps a good planted cert, which shows the checks above are what trigger replacement`() {
        plantCert(days = 365, san = "DNS:openbao,DNS:localhost,IP:127.0.0.1")
        assertThat(runSetupTls()).contains("Reusing existing OpenBao listener certificate")
        assertThat(sh("openssl x509 -in $OPENBAO/openbao-ca.pem -noout -subject")).contains("planted CA")
    }
}
