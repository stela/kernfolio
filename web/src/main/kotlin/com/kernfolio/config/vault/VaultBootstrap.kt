package com.kernfolio.config.vault

import org.springframework.boot.bootstrap.BootstrapRegistry
import org.springframework.boot.bootstrap.BootstrapRegistryInitializer
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.cloud.vault.config.VaultProperties
import org.springframework.vault.authentication.ClientAuthentication
import org.springframework.vault.authentication.TokenAuthentication
import java.nio.file.Files
import java.nio.file.Path

/**
 * Hands Spring Cloud Vault its [ClientAuthentication] before its config-data
 * loader registers the default (which it does with `registerIfAbsent`, and
 * later promotes to a bean): [FileTokenAuthentication] over
 * `openbao.certs.token` when that file exists, as in docker compose. Otherwise
 * plain `spring.cloud.vault.token`, e.g. for OpenBaoIntegrationTest's
 * root token or a bare bootRun. Registered in META-INF/spring.factories.
 */
class VaultBootstrap : BootstrapRegistryInitializer {

    override fun initialize(registry: BootstrapRegistry) {
        registry.registerIfAbsent(ClientAuthentication::class.java) { context ->
            val properties = checkNotNull(context.get(VaultProperties::class.java))
            check(properties.authentication == VaultProperties.AuthenticationMethod.TOKEN) {
                "Only token authentication is set up for OpenBao, not ${properties.authentication}"
            }
            val tokenFile = checkNotNull(context.get(Binder::class.java))
                .bind("openbao.certs.token", String::class.java)
                .map { Path.of(it) }
                .orElse(null)
            // No logging here: this runs before the logging system is set up.
            if (tokenFile != null && Files.isRegularFile(tokenFile)) {
                FileTokenAuthentication(tokenFile)
            } else {
                TokenAuthentication(checkNotNull(properties.token) { "spring.cloud.vault.token is not set" })
            }
        }
    }
}
