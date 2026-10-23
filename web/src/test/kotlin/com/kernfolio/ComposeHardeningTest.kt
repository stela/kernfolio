package com.kernfolio

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Guards the container isolation in docker-compose.yml (+ the dev overlay):
 * every service drops all capabilities and can't gain privileges, each one
 * mounts only its own certs volume, and the uids it runs as are the ones
 * openbao/init/setup.sh gives the private keys to.
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
     * by mount target.
     */
    private val allServices: Map<String, Map<String, Any?>> by lazy {
        val base = services("docker-compose.yml")
        val dev = services("docker-compose.dev.yml")
        (base.keys + dev.keys).associateWith { name ->
            val merged = base[name].orEmpty() + dev[name].orEmpty()
            val volumes = (base[name].orEmpty().strings("volumes") + dev[name].orEmpty().strings("volumes"))
                .associateBy { it.split(':')[1] }.values.toList()
            if (volumes.isEmpty()) merged else merged + ("volumes" to volumes)
        }
    }

    private fun Map<String, Any?>.strings(key: String): List<String> =
        (this[key] as List<*>?).orEmpty().map { it.toString() }

    private val setupUids: Map<String, String> by lazy {
        val script = Files.readString(repo.resolve("openbao/init/setup.sh"))
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
    fun `only openbao-init and postgres add capabilities back, and only what they need`() {
        val expected = mapOf(
            "openbao-init" to listOf("CHOWN", "DAC_READ_SEARCH"),
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
                "openbao-init" to listOf(
                    "certs_web:/openbao/certs/web",
                    "certs_caddy:/openbao/certs/caddy",
                    "certs_optimizer:/openbao/certs/optimizer",
                    "certs_postgres:/openbao/certs/postgres",
                    "certs_openbao:/openbao/certs/openbao",
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
    fun `services run as the uids setup_sh gives their keys to`() {
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
}
