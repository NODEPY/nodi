package dev.nodi.android

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.Closeable
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Date
import java.util.UUID
import javax.security.auth.x500.X500Principal

/** One fresh, non-exportable signing key per receiver session. */
class NodiIdentity private constructor(val key: PrivateKey, val certificate: X509Certificate, private val alias: String, private val persistent: Boolean = false) : Closeable {
    override fun close() {
        if (persistent) return
        synchronized(activeAliases) {
            try { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias) }
            catch (_: Exception) {}
            activeAliases.remove(alias)
        }
    }
    companion object {
        private const val PREFIX = "dev.nodi.receive.tls."
        private val activeAliases = mutableSetOf<String>()
        fun create(persistent: Boolean = false): NodiIdentity = synchronized(activeAliases) {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val old = aliases.nextElement()
                if (old.startsWith(PREFIX) && old !in activeAliases) store.deleteEntry(old)
            }
            val alias = if (persistent) "dev.nodi.devices.identity.v1" else PREFIX + UUID.randomUUID()
            if (persistent && store.containsAlias(alias)) {
                return@synchronized NodiIdentity(store.getKey(alias, null) as PrivateKey,
                    store.getCertificate(alias) as X509Certificate, alias, true)
            }
            try {
                val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
                val now = System.currentTimeMillis()
                generator.initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    // Conscrypt hashes the TLS transcript itself, then asks AndroidKeyStore
                    // for NONEwithECDSA. DIGEST_NONE authorizes that prehashed signature.
                    // SHA-256 is still needed to generate the self-signed certificate.
                    .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384, KeyProperties.DIGEST_SHA512)
                    .setUserAuthenticationRequired(false)
                    .setCertificateSubject(X500Principal("CN=Nodi"))
                    .setCertificateSerialNumber(BigInteger(128, SecureRandom()).add(BigInteger.ONE))
                    .setCertificateNotBefore(Date(now - 24 * 60 * 60 * 1000L))
                    .setCertificateNotAfter(Date(now + (if (persistent) 3650 else 365) * 24 * 60 * 60 * 1000L))
                    .build())
                generator.generateKeyPair()
                val identity = NodiIdentity(store.getKey(alias, null) as PrivateKey, store.getCertificate(alias) as X509Certificate, alias, persistent)
                activeAliases.add(alias)
                identity
            } catch (error: Exception) {
                try { store.deleteEntry(alias) } catch (_: Exception) {}
                throw error
            }
        }
    }
}
