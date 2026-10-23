package com.kernfolio

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Properties

/**
 * Who may log in to Postgres over TCP (postgres/pg_hba_mtls.conf), checked
 * with the same pgjdbc settings as application-db-tls.yml.
 */
class PostgresTlsIntegrationTest {

    companion object {
        private val postgres = KernfolioPostgresContainer()

        private const val APP_ROLE = "v-token-app-test-role"
        private const val APP_PASSWORD = "app-role-password"
        private const val OTHER_ROLE = "someone_else"

        @JvmStatic
        @BeforeAll
        fun start() {
            postgres.start()
            connectAsSuperuser().use { conn ->
                conn.createStatement().use {
                    it.execute("CREATE ROLE \"$APP_ROLE\" LOGIN PASSWORD '$APP_PASSWORD'")
                    it.execute("CREATE ROLE $OTHER_ROLE LOGIN PASSWORD '$APP_PASSWORD'")
                }
            }
        }

        private fun connectAsSuperuser() =
            connect(PostgresLogin(postgres.superuserPassword, clientCert = "postgres-admin-client"))

        /** Like application-db-tls.yml, varying the user, password and client cert. */
        private fun connect(login: PostgresLogin, host: String = "localhost"): Connection {
            val props = Properties().apply {
                setProperty("user", login.user)
                setProperty("password", login.password)
                setProperty("sslmode", login.sslMode)
                setProperty("sslrootcert", TestPki.cert("ca").toString())
                setProperty("channelBinding", "disable")
                login.clientCert?.let {
                    setProperty("sslcert", TestPki.cert(it).toString())
                    setProperty("sslkey", TestPki.key(it).toString())
                    setProperty("pemKeyAlgorithm", "Ed25519")
                    login.socketFactory?.let { factory -> setProperty("sslfactory", factory.name) }
                }
            }
            val url = postgres.jdbcUrl.replace("//localhost:", "//$host:")
            return DriverManager.getConnection(url, props)
        }

        private fun Connection.queryString(sql: String): String = use { conn ->
            conn.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getString(1) } }
        }
    }

    private data class PostgresLogin(
        val password: String,
        val clientCert: String?,
        val user: String = KernfolioPostgresContainer.SUPERUSER,
        val sslMode: String = "verify-full",
        /** null: pgjdbc's default, as in application-db-tls.yml. */
        val socketFactory: Class<*>? = null,
    )

    @Test
    fun `superuser with the kernfolio certificate connects over TLS`() {
        val sslInfo = connectAsSuperuser().queryString(
            "SELECT ssl::text || ' ' || version || ' ' || client_dn FROM pg_stat_ssl WHERE pid = pg_backend_pid()"
        )
        assertThat(sslInfo).isEqualTo("true TLSv1.3 /CN=kernfolio")
    }

    @Test
    fun `plaintext connections are rejected`() {
        assertThatThrownBy { connect(PostgresLogin(postgres.superuserPassword, clientCert = null, sslMode = "disable")) }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("no encryption")
    }

    @Test
    fun `TLS without a client certificate is rejected`() {
        assertThatThrownBy { connect(PostgresLogin(postgres.superuserPassword, clientCert = null)) }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("connection requires a valid client certificate")
    }

    @Test
    fun `client certificate from another CA is rejected`() {
        val login = PostgresLogin(
            APP_PASSWORD, clientCert = "foreign-client", user = APP_ROLE,
            socketFactory = ForcedClientCertSocketFactory::class.java,
        )
        assertThatThrownBy { connect(login) }
            .isInstanceOf(SQLException::class.java)
            .rootCause().hasMessageContaining("unknown_ca")
    }

    @Test
    fun `superuser login with a certificate for another CN is rejected`() {
        assertThatThrownBy { connect(PostgresLogin(postgres.superuserPassword, clientCert = "app-client")) }
            .isInstanceOf(SQLException::class.java)
            // Postgres reports the failed CN check as an authentication failure.
            .hasMessageContaining("password authentication failed for user \"kernfolio\"")
    }

    @Test
    fun `dynamic app role connects with the app certificate`() {
        val user = connect(PostgresLogin(APP_PASSWORD, clientCert = "app-client", user = APP_ROLE))
            .queryString("SELECT current_user")
        assertThat(user).isEqualTo(APP_ROLE)
    }

    @Test
    fun `dynamic app role still needs its password`() {
        assertThatThrownBy { connect(PostgresLogin("wrong", clientCert = "app-client", user = APP_ROLE)) }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("password authentication failed")
    }

    @Test
    fun `roles outside the allowed patterns are rejected over TCP`() {
        assertThatThrownBy { connect(PostgresLogin(APP_PASSWORD, clientCert = "app-client", user = OTHER_ROLE)) }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("pg_hba.conf rejects connection")
    }

    @Test
    fun `client verifies the server certificate's host name`() {
        // 127.0.0.1 isn't in the server cert's SANs (postgres, localhost).
        assertThatThrownBy { connectAsSuperuserVia("127.0.0.1") }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("could not be verified")
    }

    private fun connectAsSuperuserVia(host: String) =
        connect(PostgresLogin(postgres.superuserPassword, clientCert = "postgres-admin-client"), host)
}
