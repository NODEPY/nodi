package dev.nodi.android

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.net.InetSocketAddress
import javax.net.ssl.*

/** One QR scan exchanges a distinct capability for both transfer directions. */
class NodiLink {
    @Volatile private var socket: SSLSocket? = null
    @Volatile private var cancelled = false
    fun cancel() {
        cancelled = true
        socket?.let { s -> Thread({ try { s.setSoLinger(true,0); s.close() } catch (_: Exception) {} }, "Nodi-cancel-link").start() }
    }
    fun connect(qr: NodiInvite, ownPin: String, ownPort: Int, deviceName: String): TrustedDevice {
        require(pairHex(ownPin) && ownPort in 1..65535)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null,arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw java.security.cert.CertificateException()
                if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(cert.encoded), qr.fingerprint))
                    throw java.security.cert.CertificateException("QR certificate mismatch")
            }
        }),SecureRandom())
        val s = ctx.socketFactory.createSocket() as SSLSocket; socket = s
        try {
            check(!cancelled)
            s.enabledProtocols = arrayOf("TLSv1.3"); s.soTimeout = 15_000
            s.connect(InetSocketAddress(qr.host,qr.port),10_000); s.startHandshake()
            val secret = pairNonce()
            pairWrite(s,JSONObject().put("action","link").put("token",qr.token).put("fingerprint",ownPin)
                .put("port",ownPort).put("secret",secret).put("name",deviceName.filter { !it.isISOControl() }.take(80)))
            val response = pairRead(s.inputStream.buffered())
            if (!response.optBoolean("ok")) throw ReceiveFailure("QR expired or connection rejected; show a new QR on Mac")
            check(!cancelled)
            val host = qr.host.hostAddress ?: throw ReceiveFailure("missing address")
            val endpoint = if (host.contains(':')) "[$host]:${qr.port}" else "$host:${qr.port}"
            return TrustedDevice("Nodi Mac",endpoint,qr.fingerprint.hex(),secret)
        } finally { socket = null; try { s.soTimeout = 1000; s.close() } catch (_: Exception) {} }
    }
}
