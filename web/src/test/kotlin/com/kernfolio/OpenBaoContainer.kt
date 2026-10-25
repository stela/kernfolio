package com.kernfolio

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom

/**
 * OpenBao for integration tests, started like docker-compose.dev.yml: the real
 * openbao/config/openbao.hcl (Raft, here in the container's filesystem),
 * listener.hcl (the API on 8200 over mutual TLS, with certs made by
 * setup-tls.sh in [tlsDir], by default [OpenBaoTls.dir]) and dev.hcl (static
 * unseal key, self-init creating root token [token]). It survives a restart
 * like the dev stack does.
 *
 * Testcontainers' VaultContainer only accepts `hashicorp/vault` images, so this
 * is a plain GenericContainer. Unlike VaultContainer's init commands, [bao]
 * fails the test on a non-zero exit instead of only logging it.
 */
class OpenBaoContainer(val token: String, private val tlsDir: Path = OpenBaoTls.dir) :
    GenericContainer<OpenBaoContainer>(DockerImageName.parse("openbao/openbao:2.7.1")) {

    init {
        withExposedPorts(PORT)
        // On the volume the keys are openbao-owned 0600; these root-owned
        // copies are 0644 so the `openbao` user can read them.
        for (file in OpenBaoTls.OPENBAO_FILES) {
            withCopyFileToContainer(MountableFile.forHostPath(tlsDir.resolve(file), 0b110_100_100), "/openbao/certs/$file")
        }
        for (config in CONFIGS) {
            withCopyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/config/$config"), "/openbao/config/$config")
        }
        withCopyFileToContainer(MountableFile.forHostPath(unsealKey(), 0b110_100_100), "/openbao/certs/dev-unseal.key")
        withCommand("bao", "server", *CONFIGS.map { "-config=/openbao/config/$it" }.toTypedArray())
        withEnv("BAO_DEV_ROOT_TOKEN", token)
        // Read by `bao` CLI invocations through execInContainer.
        withEnv("BAO_ADDR", "https://127.0.0.1:$PORT")
        withEnv("BAO_CACERT", "/openbao/certs/${OpenBaoTls.CA}")
        withEnv("BAO_CLIENT_CERT", "/openbao/certs/openbao-client.pem")
        withEnv("BAO_CLIENT_KEY", "/openbao/certs/openbao-client-key.pem")
        withEnv("BAO_TOKEN", token)
        // Unsealed and self-initialized. An HTTP wait can't present a client cert.
        waitingFor(Wait.forSuccessfulCommand("bao token lookup"))
    }

    /** Base URL as seen from the test JVM. */
    val address: String get() = "https://$host:${getMappedPort(PORT)}"

    /** A client that trusts this server's listener cert, and nothing else, and presents web's client cert. */
    val httpClient: HttpClient by lazy { OpenBaoTls.httpClient(tlsDir) }

    /** Runs `bao <args>` inside the container, with the root token; returns its output. */
    fun bao(vararg args: String): String {
        val result = execInContainer("bao", *args)
        check(result.exitCode == 0) { "bao ${args.joinToString(" ")} failed: ${result.stderr}" }
        return result.stdout.trim()
    }

    /** Copies openbao/init/policies/[policy].hcl in and writes it, plus a token role like setup.sh's. */
    fun writePolicyAndRole(policy: String) {
        copyFileToContainer(KernfolioPostgresContainer.repoFile("openbao/init/policies/$policy.hcl"), "/tmp/$policy.hcl")
        bao("policy", "write", policy, "/tmp/$policy.hcl")
        bao("write", "auth/token/roles/$policy", "allowed_policies=$policy", "orphan=true", "renewable=true", "token_period=720h")
    }

    companion object {
        const val PORT = 8200
        private val CONFIGS = listOf("openbao.hcl", "listener.hcl", "dev.hcl")

        /** dev.hcl's static seal key: 32 random bytes, as setup-tls.sh makes it. */
        private fun unsealKey(): Path = Files.createTempFile("openbao-dev-unseal", ".key").also {
            Files.write(it, ByteArray(32).also(SecureRandom()::nextBytes))
        }
    }
}
