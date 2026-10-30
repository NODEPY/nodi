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
    fun connect(qr: NodiInvite, ownPin: String, ownPort: Int, deviceName: String,
                confirm: ((String) -> Boolean)? = null): TrustedDevice {
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
            val client = pairNonce()
            val transcript = "nodi-wifi-v1\n$client\n$ownPin\n$ownPort\n$secret"
            val commitment = pairHash(transcript)
            pairWrite(s,JSONObject().put("action",if(confirm == null) "link" else "wifi_link")
                .put("token",if(confirm == null) qr.token else "")
                .put("commit",commitment).put("fingerprint",ownPin)
                .put("port",ownPort).put("secret",secret).put("name",deviceName.filter { !it.isISOControl() }.take(80)))
            val input = s.inputStream.buffered()
            if (confirm != null) {
                val challenge = pairRead(input)
                if (!challenge.optBoolean("pending") || !pairHex(challenge.optString("nonce")))
                    throw ReceiveFailure(challenge.optString("error", "pairing unavailable"))
                pairWrite(s, JSONObject().put("nonce",client))
                s.soTimeout = 70_000
                val allow = confirm(pairCompare(qr.fingerprint.hex(),transcript,challenge.getString("nonce")))
                if (cancelled) throw ReceiveFailure("pairing cancelled")
                pairWrite(s,JSONObject().put("allow",allow))
                if (!allow) throw ReceiveFailure("pairing declined")
            }
            val response = pairRead(input)
            if (!response.optBoolean("ok")) throw ReceiveFailure(response.optString("error","connection rejected"))
            check(!cancelled)
            val host = qr.host.hostAddress ?: throw ReceiveFailure("missing address")
            val endpoint = if (host.contains(':')) "[$host]:${qr.port}" else "$host:${qr.port}"
            return TrustedDevice(response.optString("name", "Mac").filter { !it.isISOControl() }.take(80),endpoint,qr.fingerprint.hex(),secret)
        } finally { socket = null; try { s.soTimeout = 1000; s.close() } catch (_: Exception) {} }
    }
}
