package com.kernfolio.config

import org.postgresql.ssl.WrappedFactory
import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import java.util.Properties

/**
 * pgjdbc `sslfactory` for mTLS to Postgres (application-db-tls.yml).
 *
 * pgjdbc's own LibPQFactory insists, like libpq, that the client key is 0600
 * and owned by the current user. Web runs as Paketo's uid 1002, but the key on
 * the shared openbao_certs volume belongs to OpenBao's init user and is 0644 so
 * other services can read it. This factory loads the same `sslcert`, `sslkey`
 * and `sslrootcert` PEM files with Spring's PEM support instead (which also
 * handles OpenBao's Ed25519 keys). pgjdbc still does the `sslmode=verify-full`
 * hostname check itself after the handshake.
 */
class PemSslSocketFactory(info: Properties) : WrappedFactory() {
    init {
        fun path(name: String) = requireNotNull(info.getProperty(name)) { "pgjdbc property '$name' is required" }

        val keyStore = PemSslStoreDetails.forCertificate(path("sslcert")).withPrivateKey(path("sslkey"))
        val trustStore = PemSslStoreDetails.forCertificate(path("sslrootcert"))
        factory = SslBundle.of(PemSslStoreBundle(keyStore, trustStore)).createSslContext().socketFactory
    }
}
