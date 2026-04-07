package com.kernfolio

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.kernfolio.service.EmailService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.Network
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.vault.VaultContainer
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource

private const val VAULT_TOKEN = "test-root-token"

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("vault")
class VaultProfileIntegrationTest {

    companion object {
        private val network = Network.newNetwork()

        private val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("kernfolio")
            .withUsername("kernfolio")
            .withPassword("kernfolio")
            .withNetwork(network)
            .withNetworkAliases("postgres")
            .withInitScript("vault-test-init.sql")

        private val greenMail = GreenMail(ServerSetup(0, null, ServerSetup.PROTOCOL_SMTP))

        // VaultContainer with init commands — uses the vault CLI inside the container
        // to configure the database secrets engine (mirrors setup-db.sh)
        private val vault = VaultContainer("hashicorp/vault:1.18")
            .withVaultToken(VAULT_TOKEN)
            .withNetwork(network)
            .withNetworkAliases("vault")
            .withInitCommand(
                "secrets enable database",
                """write database/config/kernfolio \
                    plugin_name=postgresql-database-plugin \
                    allowed_roles=app \
                    connection_url="postgresql://{{username}}:{{password}}@postgres:5432/kernfolio?sslmode=disable" \
                    username=kernfolio \
                    password=kernfolio""",
                """write database/roles/app \
                    db_name=kernfolio \
                    creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT ALL ON SCHEMA public TO \"{{name}}\"; GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \"{{name}}\"; GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO \"{{name}}\";" \
                    revocation_statements="DROP ROLE IF EXISTS \"{{name}}\";" \
                    default_ttl=1h max_ttl=24h""",
            )

        init {
            postgres.start()
            greenMail.start()
            vault.start()

            // Set Vault connection properties as system properties — must be available
            // before spring.config.import: vault:// triggers in the early bootstrap phase
            System.setProperty("spring.cloud.vault.uri", "http://${vault.host}:${vault.firstMappedPort}")
            System.setProperty("spring.cloud.vault.token", VAULT_TOKEN)

            // Generate dynamic DB credentials from Vault's database engine, then
            // write them (along with other secrets) into KV for Spring to consume.
            // spring.config.import: vault:// reads KV but not the database backend
            // directly, so we bridge the two.
            val (dbUsername, dbPassword) = generateDatabaseCredentials()
            writeKvSecrets(dbUsername, dbPassword)
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            greenMail.stop()
            System.clearProperty("spring.cloud.vault.uri")
            System.clearProperty("spring.cloud.vault.token")
        }

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") {
                "jdbc:postgresql://${postgres.host}:${postgres.firstMappedPort}/kernfolio"
            }
            // Disable SMTP auth for GreenMail (nested properties with dots
            // don't map cleanly from Vault KV, so set them here directly)
            registry.add("spring.mail.properties.mail.smtp.auth") { "false" }
            registry.add("spring.mail.properties.mail.smtp.starttls.enable") { "false" }
            registry.add("market-data.scheduler.enabled") { "false" }
            registry.add("optimizer.base-url") { "http://localhost:9999" }
        }

        private fun generateDatabaseCredentials(): Pair<String, String> {
            val vaultAddr = "http://${vault.host}:${vault.firstMappedPort}"
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$vaultAddr/v1/database/creds/app"))
                .header("X-Vault-Token", VAULT_TOKEN)
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "Failed to generate DB credentials: ${response.body()}" }

            val body = response.body()
            return body.substringAfter("\"username\":\"").substringBefore("\"") to
                body.substringAfter("\"password\":\"").substringBefore("\"")
        }

        private fun writeKvSecrets(dbUsername: String, dbPassword: String) {
            val vaultAddr = "http://${vault.host}:${vault.firstMappedPort}"
            val smtpPort = greenMail.smtp.port
            val request = HttpRequest.newBuilder()
                .uri(URI.create("$vaultAddr/v1/secret/data/kernfolio"))
                .header("X-Vault-Token", VAULT_TOKEN)
                .header("Content-Type", "application/json")
                .POST(
                    HttpRequest.BodyPublishers.ofString(
                        """
                        {
                          "data": {
                            "spring.datasource.username": "$dbUsername",
                            "spring.datasource.password": "$dbPassword",
                            "spring.mail.host": "localhost",
                            "spring.mail.port": "$smtpPort",
                            "session.signing-key": "test-session-signing-key",
                            "admin.email": "admin@kernfolio.dev"
                          }
                        }
                        """.trimIndent()
                    )
                )
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() in 200..299) { "Failed to write KV secrets: ${response.body()}" }
        }
    }

    @Autowired
    lateinit var environment: Environment

    @Autowired
    lateinit var dataSource: DataSource

    @Autowired
    lateinit var mailSender: JavaMailSender

    @Autowired
    lateinit var emailService: EmailService

    @Test
    fun `application context loads with vault profile`() {
        assertThat(environment.activeProfiles).contains("vault")
    }

    @Test
    fun `vault database credentials allow database access`() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT current_user")
                assertThat(rs.next()).isTrue()
                val username = rs.getString(1)
                // Vault database engine generates usernames with v-token-{role}- prefix
                assertThat(username).startsWith("v-token-app-")
            }
        }
    }

    @Test
    fun `liquibase migrations run with vault-issued credentials`() {
        dataSource.connection.use { conn ->
            val tables = conn.metaData.getTables(null, "public", "users", null)
            assertThat(tables.next()).isTrue()
        }
    }

    @Test
    fun `KV secrets are available in Spring environment`() {
        assertThat(environment.getProperty("session.signing-key"))
            .isEqualTo("test-session-signing-key")
        assertThat(environment.getProperty("admin.email"))
            .isEqualTo("admin@kernfolio.dev")
    }

    @Test
    fun `JavaMailSender is configured from vault SMTP secrets`() {
        assertThat(mailSender).isNotNull()
        val impl = mailSender as org.springframework.mail.javamail.JavaMailSenderImpl
        assertThat(impl.host).isEqualTo("localhost")
        assertThat(impl.port).isEqualTo(greenMail.smtp.port)
    }

    @Test
    fun `invite code email is sent via vault-configured SMTP`() {
        emailService.sendInviteCode("newuser@example.com", "ABCD1234", "http://localhost:8080")

        val messages = greenMail.receivedMessages
        assertThat(messages).hasSize(1)

        val msg = messages[0]
        assertThat(msg.subject).isEqualTo("Your Kernfolio Invite")
        assertThat(msg.allRecipients[0].toString()).isEqualTo("newuser@example.com")
        val body = msg.content as String
        assertThat(body).contains("ABCD1234")
        assertThat(body).contains("http://localhost:8080/register")
    }
}
