package dev.nodi.android

import org.json.JSONObject
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class PairRequest(val id: String, val name: String, val compare: String)
internal fun pairHex(value: String) = value.matches(Regex("[a-f0-9]{64}"))
internal fun pairNonce() = ByteArray(32).also { SecureRandom().nextBytes(it) }.hex()
internal fun pairHash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
internal fun pairCompare(pin: String, client: String, server: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest("nodi-pair-v1\n${pin.lowercase()}\n$client\n$server".toByteArray(Charsets.UTF_8))
    val number = bytes.take(4).fold(0L) { n, b -> (n shl 8) or (b.toLong() and 255) }
    return (number % 1_000_000).toString().padStart(6, '0')
}
internal fun pairRead(input: InputStream): JSONObject {
    val bytes = ByteArrayOutputStream()
    while (bytes.size() < 4096) {
        val b = input.read()
        if (b < 0) throw ReceiveFailure("pairing disconnected")
        if (b == 10) return JSONObject(String(bytes.toByteArray(), Charsets.UTF_8))
        bytes.write(b)
    }
    throw ReceiveFailure("pairing message too large")
}
internal fun pairWrite(socket: SSLSocket, json: JSONObject) {
    socket.outputStream.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
    socket.outputStream.flush()
}
class NodiPairClient {
    private val cancelled = AtomicBoolean(false)
    @Volatile private var socket: SSLSocket? = null
    fun cancel() {
        cancelled.set(true)
        socket?.let { s -> Thread({ try { s.setSoLinger(true, 0); s.close() } catch (_: Exception) {} }, "Nodi-stop-pair").start() }
    }
    fun connect(host: InetAddress, port: Int, pin: String, name: String, onCompare: (String) -> Unit): String {
        require(pairHex(pin) && port in 1..65535 && !host.isAnyLocalAddress)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) { throw java.security.cert.CertificateException() }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw java.security.cert.CertificateException()
                val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded).hex()
                if (!MessageDigest.isEqual(actual.toByteArray(), pin.toByteArray())) throw java.security.cert.CertificateException("receiver certificate mismatch")
            }
        }), SecureRandom())
        val s = context.socketFactory.createSocket() as SSLSocket
        socket = s
        try {
            if (cancelled.get()) throw ReceiveFailure("pairing cancelled")
            s.enabledProtocols = arrayOf("TLSv1.3")
            s.soTimeout = 15_000
            s.connect(InetSocketAddress(host, port), 10_000)
            s.startHandshake()
            val client = pairNonce()
            pairWrite(s, JSONObject().put("action", "pair").put("name", name.take(80)).put("commit", pairHash(client)))
            val input = s.inputStream.buffered()
            val challenge = pairRead(input)
            val server = challenge.optString("nonce")
            if (!challenge.optBoolean("pending") || !pairHex(server)) throw ReceiveFailure("pairing unavailable; use QR")
            pairWrite(s, JSONObject().put("nonce", client))
            onCompare(pairCompare(pin, client, server))
            s.soTimeout = 70_000
            val response = pairRead(input)
            val token = response.optString("token")
            if (!response.optBoolean("ok") || !pairHex(token)) throw ReceiveFailure("pairing declined or timed out")
            if (cancelled.get()) throw ReceiveFailure("pairing cancelled")
            val address = host.hostAddress ?: throw ReceiveFailure("no address")
            val endpoint = if (address.contains(':')) "[$address]:$port" else "$address:$port"
            return "nodi1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(JSONObject()
                .put("address", endpoint).put("token", token).put("fingerprint", pin).toString().toByteArray(Charsets.UTF_8))
        } finally { socket = null; try { s.soTimeout = 1000; s.close() } catch (_: Exception) {} }
    }
}
