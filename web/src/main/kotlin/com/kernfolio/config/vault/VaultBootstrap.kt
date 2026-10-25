package com.kernfolio.config.vault

import com.kernfolio.config.tls.ReloadingPemKeyManager
import org.springframework.boot.bootstrap.BootstrapContext
import org.springframework.boot.bootstrap.BootstrapRegistry
import org.springframework.boot.bootstrap.BootstrapRegistryInitializer
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.ssl.pem.PemContent
import org.springframework.cloud.vault.config.VaultProperties
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.vault.authentication.ClientAuthentication
import org.springframework.vault.authentication.TokenAuthentication
import org.springframework.vault.config.AbstractVaultConfiguration.ClientFactoryWrapper
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * Hands Spring Cloud Vault two things before its config-data loader registers
 * its defaults (which it does with `registerIfAbsent`, and later promotes to
 * beans). Registered in META-INF/spring.factories.
 *
 * - [ClientAuthentication]: [FileTokenAuthentication] over
 *   `openbao.certs.token` when that file exists, as in docker compose.
 *   Otherwise plain `spring.cloud.vault.token`, e.g. for a bare bootRun.
 * - The HTTP client, over mutual TLS with OpenBao's listener: it trusts
 *   `openbao.certs.openbao-ca` and presents `openbao.certs.openbao-client-cert`
 *   + `-key` through a [ReloadingPemKeyManager], so a cert renewed by
 *   cert-renewer is used without a restart. Spring Cloud Vault's own
 *   `spring.cloud.vault.ssl.*` can't do either: it loads a key store once,
 *   and can't read a PEM private key. A missing file means the JDK's default
 *   trust or no client cert.
 *
 * No logging here: this runs before the logging system is set up.
 */
class VaultBootstrap : BootstrapRegistryInitializer {

    override fun initialize(registry: BootstrapRegistry) {
        registry.registerIfAbsent(ClientAuthentication::class.java) { context ->
            val properties = context.vaultProperties()
            check(properties.authentication == VaultProperties.AuthenticationMethod.TOKEN) {
                "Only token authentication is set up for OpenBao, not ${properties.authentication}"
            }
            val tokenFile = context.existingFile("openbao.certs.token")
            if (tokenFile != null) {
                FileTokenAuthentication(tokenFile)
            } else {
                TokenAuthentication(checkNotNull(properties.token) { "spring.cloud.vault.token is not set" })
            }
        }

        registry.registerIfAbsent(ClientFactoryWrapper::class.java) { context ->
            val properties = context.vaultProperties()
            val ca = context.existingFile("openbao.certs.openbao-ca")
            val cert = context.existingFile("openbao.certs.openbao-client-cert")
            val key = context.existingFile("openbao.certs.openbao-client-key")
            val keyManagers = if (cert != null && key != null) arrayOf(ReloadingPemKeyManager(cert, key)) else null
            val trustManagers = ca?.let { trustOnly(it) }
            val sslContext = SSLContext.getInstance("TLS").apply { init(keyManagers, trustManagers, null) }
            val client = HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofMillis(properties.connectionTimeout.toLong()))
                .build()
            ClientFactoryWrapper(
                JdkClientHttpRequestFactory(client).apply { setReadTimeout(Duration.ofMillis(properties.readTimeout.toLong())) },
            )
        }
    }

    private fun BootstrapContext.vaultProperties(): VaultProperties = checkNotNull(get(VaultProperties::class.java))

    /** The path in property [name], if it's set and the file exists. */
    private fun BootstrapContext.existingFile(name: String): Path? =
        checkNotNull(get(Binder::class.java)).bind(name, String::class.java)
            .map { Path.of(it) }
            .orElse(null)
            ?.takeIf { Files.isRegularFile(it) }

    private fun trustOnly(ca: Path) = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            PemContent.load(ca).getCertificates().forEachIndexed { i, it -> setCertificateEntry("openbao-ca-$i", it) }
        })
    }.trustManagers
}
