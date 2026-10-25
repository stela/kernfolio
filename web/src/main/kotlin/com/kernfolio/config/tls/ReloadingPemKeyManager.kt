package com.kernfolio.config.tls

import org.slf4j.LoggerFactory
import org.springframework.boot.ssl.pem.PemContent
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.time.Duration
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * A client (or server) identity from a PEM cert chain + private key that
 * follows the files when cert-renewer replaces them, so a long-running client
 * never presents an expired cert. Each handshake asks for an alias, and then
 * at most every [checkInterval] the files' size, mtime and inode are compared
 * with what was loaded; on a change they're read again.
 *
 * The cert and key are replaced one after the other, so a reload can catch a
 * mismatched pair: then the current identity stays, and the next check tries
 * again. Choosing the cert and key is left to the JDK's own key manager over
 * the loaded pair. Each load gets its own alias, so a handshake that chose an
 * alias just before a reload still gets that alias's cert and key.
 *
 * The first load has to succeed: a missing or broken pair at start is an error.
 */
class ReloadingPemKeyManager(
    private val certFile: Path,
    private val keyFile: Path,
    private val checkInterval: Duration = Duration.ofSeconds(10),
) : X509ExtendedKeyManager() {

    private class Identity(val alias: String, val stamp: List<Any?>, val keyManager: X509ExtendedKeyManager)

    private val log = LoggerFactory.getLogger(ReloadingPemKeyManager::class.java)
    private var generation = 0

    @Volatile private var current: Identity = load(stamp())
    @Volatile private var previous: Identity? = null
    @Volatile private var nextCheck: Long = System.nanoTime() + checkInterval.toNanos()

    /** Serial number of the cert currently presented, e.g. for tests and logs. */
    val serialNumber get() = current.keyManager.getCertificateChain(current.alias).first().serialNumber

    private fun stamp(): List<Any?> = listOf(certFile, keyFile).flatMap {
        val attributes = Files.readAttributes(it, BasicFileAttributes::class.java)
        listOf(attributes.fileKey(), attributes.lastModifiedTime(), attributes.size())
    }

    private fun load(stamp: List<Any?>): Identity {
        val chain = PemContent.load(certFile).getCertificates()
        val key = checkNotNull(PemContent.load(keyFile).getPrivateKey()) { "no private key in $keyFile" }
        check(chain.isNotEmpty()) { "no certificate in $certFile" }
        check(belongTogether(chain.first(), key)) { "$keyFile is not the key of $certFile" }
        val alias = "pem-${++generation}"
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry(alias, key, CharArray(0), chain.toTypedArray())
        }
        val keyManager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, CharArray(0)) }
            .keyManagers.single() as X509ExtendedKeyManager
        return Identity(alias, stamp, keyManager)
    }

    /** Re-reads the files if they changed since the last load, at most every [checkInterval]. */
    private fun refresh(): Identity {
        val now = System.nanoTime()
        if (now - nextCheck < 0) return current
        synchronized(this) {
            if (now - nextCheck < 0) return current
            nextCheck = now + checkInterval.toNanos()
            try {
                val stamp = stamp()
                if (stamp != current.stamp) {
                    previous = current
                    current = load(stamp)
                    log.info("Reloaded {}, serial {}", certFile, serialNumber.toString(16))
                }
            } catch (e: Exception) {
                log.warn("Keeping the current certificate: couldn't reload {} / {}: {}", certFile, keyFile, e.message)
            }
            return current
        }
    }

    private fun identity(alias: String?): Identity? = listOfNotNull(current, previous).find { it.alias == alias }

    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? =
        refresh().keyManager.chooseClientAlias(keyType, issuers, socket)

    override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        refresh().keyManager.chooseEngineClientAlias(keyType, issuers, engine)

    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? =
        refresh().keyManager.chooseServerAlias(keyType, issuers, socket)

    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? =
        refresh().keyManager.chooseEngineServerAlias(keyType, issuers, engine)

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        refresh().keyManager.getClientAliases(keyType, issuers)

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        refresh().keyManager.getServerAliases(keyType, issuers)

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
        identity(alias)?.keyManager?.getCertificateChain(alias)

    override fun getPrivateKey(alias: String?): PrivateKey? = identity(alias)?.keyManager?.getPrivateKey(alias)

    private companion object {
        /** Signs with the key and verifies with the cert: works for Ed25519, EC and RSA alike. */
        fun belongTogether(cert: X509Certificate, key: PrivateKey): Boolean {
            val algorithm = when (key.algorithm) {
                "EC" -> "SHA256withECDSA"
                "RSA" -> "SHA256withRSA"
                else -> key.algorithm // EdDSA, Ed25519
            }
            val data = "kernfolio".toByteArray()
            val signature = Signature.getInstance(algorithm).apply { initSign(key); update(data) }.sign()
            return try {
                Signature.getInstance(algorithm).apply { initVerify(cert.publicKey); update(data) }.verify(signature)
            } catch (_: Exception) {
                false
            }
        }
    }
}
