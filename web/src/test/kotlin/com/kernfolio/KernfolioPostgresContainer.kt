package com.kernfolio

import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.nio.file.Path

/**
 * Postgres configured exactly like the compose `postgres` service: the real
 * postgres/docker-entrypoint-tls.sh, pg_hba_mtls.conf and init-extensions.sql
 * from the repo, and [TestPki] certs at the /openbao/certs paths they expect.
 * So TCP logins need TLS, a client cert from the test CA, and a password.
 *
 * Reach it as `localhost` (in the server cert's SANs), or as `postgres` from
 * other containers on the same network.
 */
class KernfolioPostgresContainer(val superuserPassword: String = "kernfolio") :
    GenericContainer<KernfolioPostgresContainer>(DockerImageName.parse("postgres:18-alpine")) {

    init {
        withExposedPorts(PORT)
        withEnv("POSTGRES_DB", DATABASE)
        withEnv("POSTGRES_USER", SUPERUSER)
        withEnv("POSTGRES_PASSWORD", superuserPassword)
        withEnv("PGDATA", "/var/lib/postgresql/data")
        withCopyFileToContainer(repoFile("postgres/docker-entrypoint-tls.sh", 0b111_101_101), "/usr/local/bin/docker-entrypoint-tls.sh")
        withCopyFileToContainer(repoFile("postgres/pg_hba_mtls.conf"), "/etc/postgresql/pg_hba.conf")
        withCopyFileToContainer(repoFile("postgres/init-extensions.sql"), "/docker-entrypoint-initdb.d/init-extensions.sql")
        for (file in listOf("ca.pem", "postgres.pem")) {
            withCopyFileToContainer(MountableFile.forHostPath(TestPki.dir.resolve(file), 0b110_100_100), "/openbao/certs/$file")
        }
        // On the volume the key is postgres-owned 0600 (openbao/init/setup.sh).
        // Testcontainers copies files as root, so install it with that owner
        // first, then start through the real entrypoint, as in compose.
        withCopyFileToContainer(MountableFile.forHostPath(TestPki.key("postgres"), 0b110_000_000), "/tmp/postgres-key.pem")
        withCreateContainerCmdModifier {
            it.withEntrypoint(
                "sh", "-c",
                "install -o postgres -g postgres -m 600 /tmp/postgres-key.pem /openbao/certs/postgres-key.pem " +
                    "&& exec /usr/local/bin/docker-entrypoint-tls.sh",
            )
        }
        // Once for initdb's temporary server, once for the real one.
        waitingFor(Wait.forLogMessage(".*database system is ready to accept connections.*\\s", 2))
    }

    val jdbcUrl: String get() = "jdbc:postgresql://localhost:${getMappedPort(PORT)}/$DATABASE"

    companion object {
        const val PORT = 5432
        const val DATABASE = "kernfolio"
        const val SUPERUSER = "kernfolio"

        /** Repo files, relative to the web module (Gradle's test working dir). */
        fun repoFile(path: String, mode: Int = 0b110_100_100): MountableFile =
            MountableFile.forHostPath(Path.of("..").resolve(path).toAbsolutePath().normalize(), mode)
    }
}
