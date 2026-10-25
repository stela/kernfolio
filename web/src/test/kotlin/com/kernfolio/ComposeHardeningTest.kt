package com.kernfolio

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the container isolation in docker-compose.yml (+ the dev overlay):
 * every service drops all capabilities and can't gain privileges, each one
 * mounts only its own certs volume, the uids it runs as are the ones
 * openbao/init/common.sh gives the private keys to, and every OpenBao client
 * reaches OpenBao over mutual TLS.
 */
class ComposeHardeningTest {

    private val repo = Path.of("..").toAbsolutePath().normalize()

    @Suppress("UNCHECKED_CAST")
    private fun services(file: String): Map<String, Map<String, Any?>> {
        // Compose's own merge tags (`!override`, `!reset`) aren't YAML SnakeYAML
        // knows; for these checks the plain value is what matters.
        val text = Files.readString(repo.resolve(file)).replace(Regex("!(override|reset)\\b"), "")
        return Yaml().load<Map<String, Any?>>(text)["services"] as Map<String, Map<String, Any?>>
    }

    /**
     * Base services with the dev overlay merged on top, plus overlay-only
     * services. Like compose: keys are replaced, except `volumes`, which merge
     * by mount target, and `environment`, which merges by variable.
     */
    private val allServices: Map<String, Map<String, Any?>> by lazy {
        val base = services("docker-compose.yml")
        val dev = services("docker-compose.dev.yml")
        (base.keys + dev.keys).associateWith { name ->
            var merged = base[name].orEmpty() + dev[name].orEmpty()
            val volumes = (base[name].orEmpty().strings("volumes") + dev[name].orEmpty().strings("volumes"))
                .associateBy { it.split(':')[1] }.values.toList()
            if (volumes.isNotEmpty()) merged = merged + ("volumes" to volumes)
            val environment = base[name].orEmpty().environment() + dev[name].orEmpty().environment()
            if (environment.isNotEmpty()) merged = merged + ("environment" to environment)
            merged
        }
    }

    private fun Map<String, Any?>.strings(key: String): List<String> =
        (this[key] as List<*>?).orEmpty().map { it.toString() }

    private val setupUids: Map<String, String> by lazy {
        val script = Files.readString(repo.resolve("openbao/init/common.sh"))
        Regex("""^([A-Z]+)_UID=(\d+)""", RegexOption.MULTILINE).findAll(script)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    @Test
    fun `every service drops all capabilities and blocks privilege gain`() {
        assertThat(allServices).isNotEmpty
        for ((name, service) in allServices) {
            assertThat(service.strings("cap_drop")).describedAs("$name cap_drop").contains("ALL")
            assertThat(service.strings("security_opt")).describedAs("$name security_opt").contains("no-new-privileges:true")
        }
    }

    @Test
    fun `only the init containers and postgres add capabilities back, and only what they need`() {
        val expected = mapOf(
            "openbao-tls-init" to listOf("CHOWN", "DAC_READ_SEARCH"),
            "openbao-init" to listOf("CHOWN", "DAC_READ_SEARCH"),
            "cert-renewer" to listOf("CHOWN", "DAC_READ_SEARCH"),
            "postgres" to listOf("CHOWN", "DAC_READ_SEARCH", "FOWNER", "SETUID", "SETGID"),
        )
        for ((name, service) in allServices) {
            assertThat(service.strings("cap_add")).describedAs("$name cap_add")
                .containsExactlyInAnyOrderElementsOf(expected[name].orEmpty())
        }
    }

    @Test
    fun `each service mounts only its own certs volume, read-only`() {
        val certsMounts = allServices.mapValues { (_, service) ->
            service.strings("volumes").filter { it.startsWith("certs_") }
        }.filterValues { it.isNotEmpty() }

        assertThat(certsMounts).isEqualTo(
            mapOf(
                "openbao" to listOf("certs_openbao:/openbao/certs:ro"),
                // Writes OpenBao's listener cert, and its trust anchor for web.
                "openbao-tls-init" to listOf(
                    "certs_openbao:/openbao/certs/openbao",
                    "certs_web:/openbao/certs/web",
                ),
                "openbao-init" to listOf(
                    "certs_web:/openbao/certs/web",
                    "certs_caddy:/openbao/certs/caddy",
                    "certs_optimizer:/openbao/certs/optimizer",
                    "certs_postgres:/openbao/certs/postgres",
                    "certs_openbao:/openbao/certs/openbao",
                    "certs_renewer:/openbao/certs/renewer",
                ),
                // Renews them all, in the same layout as openbao-init.
                "cert-renewer" to listOf(
                    "certs_web:/openbao/certs/web",
                    "certs_caddy:/openbao/certs/caddy",
                    "certs_optimizer:/openbao/certs/optimizer",
                    "certs_postgres:/openbao/certs/postgres",
                    "certs_openbao:/openbao/certs/openbao",
                    "certs_renewer:/openbao/certs/renewer",
                ),
                "postgres" to listOf("certs_postgres:/openbao/certs:ro"),
                "openbao-db-init" to listOf("certs_openbao:/openbao/certs:ro"),
                "optimizer" to listOf("certs_optimizer:/openbao/certs:ro"),
                "web" to listOf("certs_web:/openbao/certs:ro"),
                "caddy" to listOf("certs_caddy:/openbao/certs:ro"),
            )
        )
    }

    @Test
    fun `only openbao-tls-init and cert-renewer have the key of OpenBao's TLS CA`() {
        val withCaKey = allServices.filterValues { service -> service.strings("volumes").any { it.startsWith("openbao_tls_ca:") } }
        assertThat(withCaKey.mapValues { (_, service) -> service.strings("volumes").filter { it.startsWith("openbao_tls_ca:") } })
            .isEqualTo(mapOf("openbao-tls-init" to listOf("openbao_tls_ca:/openbao/tls-ca"), "cert-renewer" to listOf("openbao_tls_ca:/openbao/tls-ca")))
    }

    @Test
    fun `cert-renewer runs renew_sh after openbao-init, as root like it`() {
        val renewer = allServices.getValue("cert-renewer")
        val init = allServices.getValue("openbao-init")
        assertThat(renewer["entrypoint"]).isEqualTo("/openbao/init/renew.sh")
        assertThat(renewer["user"]).isEqualTo(init["user"]).isEqualTo("0:0")
        assertThat(renewer["image"]).isEqualTo(init["image"])
        assertThat(renewer["depends_on"].toString()).contains("openbao-init", "service_completed_successfully", "postgres")
        assertThat(Files.isExecutable(repo.resolve("openbao/init/renew.sh"))).isTrue()
    }

    @Test
    fun `every server picks up renewed certs by itself`() {
        // OpenBao's listener.
        assertThat(Files.readString(repo.resolve("openbao/config/listener.hcl")))
            .containsPattern("""(?m)^\s*tls_auto_reload\s*=\s*true""")
        // Tomcat (application-tls.yml).
        assertThat(Files.readString(repo.resolve("web/src/main/resources/application-tls.yml")))
            .containsPattern("""(?m)^\s*reload-on-update:\s*true""")
        // Caddy: run.sh, as the image's command, with nothing in compose overriding it.
        assertThat(Files.readString(repo.resolve("caddy/Dockerfile"))).contains("CMD [\"/usr/local/bin/kernfolio-caddy-run\"]")
        assertThat(allServices.getValue("caddy").keys).doesNotContain("entrypoint", "command")
        assertThat(Files.isExecutable(repo.resolve("caddy/run.sh"))).isTrue()
        // uvicorn: run_mtls.py's ReloadingConfig (optimizer/app/tls_reload.py).
        assertThat(Files.readString(repo.resolve("optimizer/run_mtls.py"))).contains("ReloadingConfig(")
        // Spring Cloud Vault's own SSL settings can't reload, and would be ignored.
        assertThat(Files.readString(repo.resolve("web/src/main/resources/application.yml"))).doesNotContainPattern("""(?m)^\s+ssl:""")
    }

    @Test
    fun `services run as the uids common_sh gives their keys to`() {
        // postgres has no `user:`: the image starts as root and drops to 70 itself.
        val expected = mapOf(
            "web" to "WEB", "caddy" to "CADDY", "optimizer" to "OPTIMIZER",
            "openbao" to "OPENBAO", "openbao-db-init" to "OPENBAO",
        )
        for ((service, uidName) in expected) {
            val uid = allServices.getValue(service)["user"].toString().substringBefore(':')
            assertThat(uid).describedAs("$service user").isEqualTo(setupUids.getValue(uidName))
        }
        assertThat(setupUids["POSTGRES"]).isEqualTo("70")
    }

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.environment(): Map<String, String> =
        (this["environment"] as Map<String, Any?>?).orEmpty().mapValues { it.value.toString() }

    @Test
    fun `every OpenBao client uses https, trusts the listener's anchor and has a client cert`() {
        val clients = allServices.filterValues { "BAO_ADDR" in it.environment() }
        assertThat(clients.keys).contains("openbao", "openbao-init", "openbao-db-init", "web")
        for ((name, service) in clients) {
            val env = service.environment()
            assertThat(env.getValue("BAO_ADDR")).describedAs("$name BAO_ADDR").startsWith("https://")
            // web uses spring.cloud.vault.ssl.* instead (OpenBaoIntegrationTest).
            if (name != "web") {
                assertThat(env["BAO_CACERT"]).describedAs("$name BAO_CACERT").endsWith("/openbao-ca.pem")
                assertThat(env["BAO_CLIENT_CERT"]).describedAs("$name BAO_CLIENT_CERT").endsWith("/openbao-client.pem")
                assertThat(env["BAO_CLIENT_KEY"]).describedAs("$name BAO_CLIENT_KEY").endsWith("/openbao-client-key.pem")
            }
        }
    }

    @Test
    fun `prod has no dev token, and only the init containers get BAO_INIT_TOKEN, from the operator`() {
        val prodText = Files.readString(repo.resolve("docker-compose.yml"))
        assertThat(prodText).doesNotContain("dev-root-token", "BAO_APP_TOKEN", "SPRING_CLOUD_VAULT_TOKEN")
        val prod = services("docker-compose.yml")
        val withInitToken = prod.filterValues { "BAO_INIT_TOKEN" in it.environment() }
        assertThat(withInitToken.keys).containsExactlyInAnyOrder("openbao-init", "openbao-db-init")
        for ((name, service) in withInitToken) {
            assertThat(service.environment().getValue("BAO_INIT_TOKEN")).describedAs(name).isEqualTo("\${BAO_INIT_TOKEN:-}")
        }
        // No service gets a token through its environment, other than those two.
        for ((name, service) in allServices) {
            if (name in withInitToken || name == "openbao") continue
            assertThat(service.environment().keys).describedAs("$name env")
                .noneMatch { it.contains("TOKEN") }
        }
        assertThat(Files.readString(repo.resolve("web/src/main/resources/application.yml"))).doesNotContain("dev-root-token")
    }

    @Test
    fun `openbao serves listener_hcl in prod and dev, after openbao-tls-init`() {
        val prod = services("docker-compose.yml").getValue("openbao")
        val prodConfigs = listOf("-config=/openbao/config/openbao.hcl", "-config=/openbao/config/listener.hcl")
        assertThat(prod["command"].toString()).contains(prodConfigs).doesNotContain("dev.hcl", "-dev")
        // Dev is prod's config plus dev.hcl, never `-dev`: that would add a
        // plain-HTTP listener and keep state in memory.
        assertThat(allServices.getValue("openbao")["command"].toString())
            .contains(prodConfigs + "-config=/openbao/config/dev.hcl")
            .doesNotContainPattern("""\s-dev(\s|-|$)""")
        assertThat(prod["depends_on"].toString()).contains("openbao-tls-init")
    }

    @Test
    fun `only dev_hcl unseals and initializes itself`() {
        val config = repo.resolve("openbao/config")
        for (file in listOf("openbao.hcl", "listener.hcl")) {
            assertThat(Files.readString(config.resolve(file))).describedAs(file)
                .doesNotContainPattern("""(?m)^\s*(seal|initialize)\s""")
        }
        assertThat(Files.readString(config.resolve("dev.hcl")))
            .containsPattern("""(?m)^seal "static"""")
            .containsPattern("""(?m)^initialize """)
    }

    @Test
    fun `OpenBao's listener requires client certs from its TLS CA`() {
        val listener = Files.readString(repo.resolve("openbao/config/listener.hcl"))
        assertThat(listener).containsPattern("""(?m)^\s*tls_require_and_verify_client_cert\s*=\s*true""")
        assertThat(listener).containsPattern("""(?m)^\s*tls_client_ca_file\s*=\s*"/openbao/certs/openbao-ca.pem"""")
        assertThat(listener).doesNotContain("tls_disable")
    }
}
