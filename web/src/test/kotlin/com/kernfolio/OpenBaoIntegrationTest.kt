package com.kernfolio

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import com.kernfolio.service.EmailService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.health.contributor.HealthIndicator
import org.springframework.boot.health.contributor.ReactiveHealthIndicator
import org.springframework.boot.health.contributor.Status
import org.springframework.context.ApplicationContext
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.vault.core.VaultOperations
import org.testcontainers.containers.Network
import org.testcontainers.utility.MountableFile
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import javax.sql.DataSource

private const val BAO_TOKEN = "test-root-token"

/**
 * The real OpenBao integration, end to end over mTLS: OpenBao's database engine
 * connects to [KernfolioPostgresContainer] with the superuser client cert (as
 * set up by openbao/init/setup-db.sh), and Spring's datasource connects with the
 * dynamic credentials and the app cert through the real `db-tls` profile.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.cloud.vault.enabled=true",
        "spring.cloud.vault.config.lifecycle.enabled=true",
        "spring.config.import=vault://",
    ],
)
@ActiveProfiles("db-tls")
class OpenBaoIntegrationTest {
    // Spring Cloud Vault is off by default for tests (see web/build.gradle.kts).
    // This test is the one that exercises the real OpenBao integration, so it
    // re-enables it and adds the vault:// config import that dev/prod declare.

    companion object {
        private val network = Network.newNetwork()

        private val postgres = KernfolioPostgresContainer()
            .withNetwork(network)
            .withNetworkAliases("postgres")

        private val greenMail = GreenMail(ServerSetup(0, null, ServerSetup.PROTOCOL_SMTP))

        private val openBao = OpenBaoContainer(BAO_TOKEN)
            .withNetwork(network)
            .withNetworkAliases("openbao")
            .apply {
                // As the compose openbao service mounts openbao_certs. pgx doesn't
                // check key file permissions; 0644 lets the `openbao` user read it.
                for (file in listOf("ca.pem", "postgres-admin-client.pem", "postgres-admin-client-key.pem")) {
                    withCopyFileToContainer(MountableFile.forHostPath(TestPki.dir.resolve(file), 0b110_100_100), "/openbao/certs/$file")
                }
            }

        init {
            postgres.start()
            greenMail.start()
            openBao.start()
            configureDatabaseEngine()

            // Set OpenBao connection properties as system properties — must be available
            // before spring.config.import: vault:// triggers in the early bootstrap phase
            System.setProperty("spring.cloud.vault.uri", openBao.address)
            System.setProperty("spring.cloud.vault.token", BAO_TOKEN)

            // Generate dynamic DB credentials from OpenBao's database engine, then
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
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            // Where application-db-tls.yml (and OptimizerClientConfig) find certs.
            registry.add("openbao.certs.dir") { TestPki.dir.toString() }
            // Disable SMTP auth for GreenMail (nested properties with dots
            // don't map cleanly from OpenBao KV, so set them here directly)
            registry.add("spring.mail.properties.mail.smtp.auth") { "false" }
            registry.add("spring.mail.properties.mail.smtp.starttls.enable") { "false" }
            registry.add("market-data.scheduler.enabled") { "false" }
            registry.add("optimizer.base-url") { "http://localhost:9999" }
        }

        /** Mirrors openbao/init/setup-db.sh (TLS, username template), minus the app_owner group role. */
        private fun configureDatabaseEngine() {
            openBao.bao("secrets", "enable", "database")
            openBao.bao(
                "write", "database/config/kernfolio",
                "plugin_name=postgresql-database-plugin",
                "allowed_roles=app",
                "connection_url=postgresql://{{username}}:{{password}}@postgres:5432/kernfolio?sslmode=verify-full" +
                    "&sslrootcert=/openbao/certs/ca.pem" +
                    "&sslcert=/openbao/certs/postgres-admin-client.pem" +
                    "&sslkey=/openbao/certs/postgres-admin-client-key.pem" +
                    "&channel_binding=disable",
                "username_template={{ printf \"v-token-app-%s-%s\" (random 20) (unix_time) | truncate 63 }}",
                "username=kernfolio",
                "password=${postgres.superuserPassword}",
            )
            openBao.bao(
                "write", "database/roles/app",
                "db_name=kernfolio",
                "creation_statements=CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; " +
                    "GRANT ALL ON SCHEMA public TO \"{{name}}\"; " +
                    "GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO \"{{name}}\"; " +
                    "GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO \"{{name}}\";",
                "revocation_statements=DROP ROLE IF EXISTS \"{{name}}\";",
                "default_ttl=1h",
                "max_ttl=24h",
            )
        }

        private fun generateDatabaseCredentials(): Pair<String, String> {
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${openBao.address}/v1/database/creds/app"))
                .header("X-Vault-Token", BAO_TOKEN)
                .GET()
                .build()
            val response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            check(response.statusCode() == 200) { "Failed to generate DB credentials: ${response.body()}" }

            val body = response.body()
            return body.substringAfter("\"username\":\"").substringBefore("\"") to
                body.substringAfter("\"password\":\"").substringBefore("\"")
        }

        private fun writeKvSecrets(dbUsername: String, dbPassword: String) {
            val smtpPort = greenMail.smtp.port
            val request = HttpRequest.newBuilder()
                .uri(URI.create("${openBao.address}/v1/secret/data/kernfolio"))
                .header("X-Vault-Token", BAO_TOKEN)
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

    @Autowired
    lateinit var vaultOperations: VaultOperations

    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Test
    fun `application context loads with OpenBao enabled`() {
        assertThat(environment.getProperty("spring.cloud.vault.enabled"))
            .isEqualTo("true")
    }

    @Test
    fun `OpenBao database credentials allow database access`() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT current_user")
                assertThat(rs.next()).isTrue()
                val username = rs.getString(1)
                // The database engine generates usernames with v-token-{role}- prefix
                assertThat(username).startsWith("v-token-app-")
            }
        }
    }

    @Test
    fun `datasource connects over mTLS with the app certificate`() {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT ssl, client_dn FROM pg_stat_ssl WHERE pid = pg_backend_pid()")
                assertThat(rs.next()).isTrue()
                assertThat(rs.getBoolean(1)).isTrue()
                assertThat(rs.getString(2)).isEqualTo("/CN=app")
            }
        }
    }

    @Test
    fun `liquibase migrations run with OpenBao-issued credentials`() {
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
    fun `JavaMailSender is configured from OpenBao SMTP secrets`() {
        assertThat(mailSender).isNotNull()
        val impl = mailSender as org.springframework.mail.javamail.JavaMailSenderImpl
        assertThat(impl.host).isEqualTo("localhost")
        assertThat(impl.port).isEqualTo(greenMail.smtp.port)
    }

    @Test
    fun `invite code email is sent via OpenBao-configured SMTP`() {
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

    // OpenBao 2.6 dropped `performance_standby` from GET /sys/health, and
    // spring-vault-core <= 4.0.3 failed to deserialise the response
    // (spring-projects/spring-vault#1048). These two guard against a downgrade.
    @Test
    fun `sys health response from OpenBao deserialises`() {
        val health = vaultOperations.opsForSys().health()
        assertThat(health.isInitialized).isTrue()
        assertThat(health.isSealed).isFalse()
        assertThat(health.isPerformanceStandby).isFalse()
    }

    @Test
    fun `actuator health indicator reports OpenBao as up`() {
        val status = when (val indicator = applicationContext.getBean("vaultHealthIndicator")) {
            is HealthIndicator -> indicator.health()?.status
            is ReactiveHealthIndicator -> indicator.health().block()?.status
            else -> error("Unexpected health contributor type: ${indicator::class}")
        }
        assertThat(status).isEqualTo(Status.UP)
    }
}
