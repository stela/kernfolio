package com.kernfolio

import org.postgresql.ssl.WrappedFactory
import org.springframework.boot.ssl.SslBundle
import org.springframework.boot.ssl.pem.PemSslStoreBundle
import org.springframework.boot.ssl.pem.PemSslStoreDetails
import java.net.Socket
import java.nio.file.Path
import java.security.Principal
import java.util.Properties
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Client SSLContexts that always present the given cert, even if the server's
 * CertificateRequest names other CAs. JSSE's default key manager would quietly
 * send no cert in that case, so a "foreign CA cert is rejected" test would only
 * prove that the client gave up. OpenSSL-based clients (libpq, curl, Python) do
 * send it, which is the case these tests need the server to reject.
 */
object ForcedClientCert {
    fun sslContext(cert: Path, key: Path, trustedCa: Path): SSLContext {
        val bundle = SslBundle.of(
            PemSslStoreBundle(
                PemSslStoreDetails.forCertificate(cert.toString()).withPrivateKey(key.toString()),
                PemSslStoreDetails.forCertificate(trustedCa.toString()),
            )
        )
        val managers = bundle.managers
        val keyManagers: Array<KeyManager> = managers.keyManagers
            .map { if (it is X509ExtendedKeyManager) IgnoreIssuers(it) else it }
            .toTypedArray()
        return SSLContext.getInstance(bundle.protocol).apply { init(keyManagers, managers.trustManagers, null) }
    }

    private class IgnoreIssuers(private val delegate: X509ExtendedKeyManager) : X509ExtendedKeyManager() {
        private fun anyAlias(keyTypes: Array<String>?) =
            keyTypes.orEmpty().firstNotNullOfOrNull { delegate.getClientAliases(it, null)?.firstOrNull() }

        override fun chooseClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, socket: Socket?) = anyAlias(keyType)
        override fun chooseEngineClientAlias(keyType: Array<String>?, issuers: Array<Principal>?, engine: SSLEngine?) = anyAlias(keyType)
        override fun getClientAliases(keyType: String?, issuers: Array<Principal>?) = delegate.getClientAliases(keyType, null)
        override fun getServerAliases(keyType: String?, issuers: Array<Principal>?) = delegate.getServerAliases(keyType, issuers)
        override fun chooseServerAlias(keyType: String?, issuers: Array<Principal>?, socket: Socket?) = delegate.chooseServerAlias(keyType, issuers, socket)
        override fun getCertificateChain(alias: String?) = delegate.getCertificateChain(alias)
        override fun getPrivateKey(alias: String?) = delegate.getPrivateKey(alias)
    }
}

/** pgjdbc `sslfactory` presenting `sslcert` via [ForcedClientCert]. */
class ForcedClientCertSocketFactory(info: Properties) : WrappedFactory() {
    init {
        factory = ForcedClientCert.sslContext(
            Path.of(info.getProperty("sslcert")),
            Path.of(info.getProperty("sslkey")),
            Path.of(info.getProperty("sslrootcert")),
        ).socketFactory
    }
}
