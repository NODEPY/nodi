package dev.nodi.android

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager

data class IncomingFile(val name: String, val size: Long, val sha256: String)
data class ReceiveEvent(val phase: String, val name: String = "", val bytes: Long = 0,
                        val total: Long = 0, val message: String = "", val diagnostic: String = "")
class ReceiveFailure(val reason: String) : IOException(reason)

/** commit must create a NEW document, verify it and return its actual display name.
 * It must not overwrite a pre-existing document, and must clean partial results on failure. */
interface ReceiveStore {
    fun checkName(name: String)
    fun commit(snapshot: File, incoming: IncomingFile, checkActive: () -> Unit): String
}

/** Pure JVM TLS server, wire-compatible with the Go sender. Run on a worker thread. */
class NodiReceiver(
    privateKey: PrivateKey,
    certificate: X509Certificate,
    private val cacheDirectory: File,
    private val store: ReceiveStore,
    private val onEvent: (ReceiveEvent) -> Unit,
    bindAddress: String = "0.0.0.0",
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    private val headerTimeoutMs: Int = 15_000,
    private val transferTimeoutMs: Long = 30 * 60 * 1000L,
    private val onPair: ((PairRequest, () -> Unit) -> Boolean)? = null,
    private val devices: DeviceBook? = null
) : Closeable {
    private val closed = AtomicBoolean(false)
    private var lastUnauthenticatedError = 0L
    private val active = CopyOnWriteArrayList<Closeable>()
    private val deadlines = Executors.newSingleThreadScheduledExecutor()
    private val token = ByteArray(32).also { SecureRandom().nextBytes(it) }.hex()
    val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).hex()
    private val listener: SSLServerSocket
    val port: Int get() = listener.localPort

    init {
        try {
            val context = SSLContext.getInstance("TLS")
            context.init(arrayOf<KeyManager>(ServerKeyManager(privateKey, certificate)), null, SecureRandom())
            listener = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
                try {
                    require("TLSv1.3" in supportedProtocols) { "TLS 1.3 is required" }
                    enabledProtocols = arrayOf("TLSv1.3")
                    needClientAuth = false
                    reuseAddress = false
                    bind(InetSocketAddress(bindAddress, 0), 2)
                } catch (error: Exception) { close(); throw error }
            }
        } catch (error: Exception) { deadlines.shutdownNow(); throw error }
    }

    fun invite(address: String): String {
        check(!closed.get())
        val endpoint = if (address.contains(':')) "[$address]:$port" else "$address:$port"
        val json = JSONObject().put("address", endpoint).put("token", token)
            .put("fingerprint", fingerprint).toString()
        return "nodi1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray(Charsets.UTF_8))
    }

    fun run() {
        try {
            while (!closed.get()) {
                val client = try { listener.accept() as SSLSocket }
                catch (error: IOException) { if (closed.get()) break else throw error }
                active.add(client)
                if (closed.get()) { client.close(); break }
                try { client.use { receive(it) } }
                finally { active.remove(client) }
            }
        } finally { close() }
    }

    private fun receive(client: SSLSocket) {
        val expired = AtomicBoolean(false)
        val deadline = deadlines.schedule({
            expired.set(true)
            try { client.setSoLinger(true, 0); client.close() } catch (_: Exception) {}
        }, transferTimeoutMs, TimeUnit.MILLISECONDS)
        var snapshot: File? = null
        var savedName: String? = null
        var authenticated = false
        var handshakeComplete = false
        var stage = "TLS"
        var deviceToken: String? = null
        fun checkActive() {
            if (closed.get() || expired.get()) throw ReceiveFailure("transfer cancelled or timed out")
            deviceToken?.let { if (devices?.stillAuthorized(it) == false) throw ReceiveFailure("device access revoked") }
        }
        try {
            client.soTimeout = headerTimeoutMs
            client.enabledProtocols = arrayOf("TLSv1.3")
            client.startHandshake()
            handshakeComplete = true
            stage = "HEADER"
            val input = client.inputStream.buffered()
            val json = try { JSONObject(readLine(input)) }
            catch (_: Exception) { throw ReceiveFailure("invalid header") }
            if (devices != null && json.optString("action") in listOf("pair", "link"))
                throw ReceiveFailure("scan Mac QR using Nodi on your phone")
            if (json.optString("action") == "pair") {
                stage = "PAIR"
                if (onPair == null || !pairHex(json.optString("commit"))) throw ReceiveFailure("pairing unavailable")
                val server = pairNonce()
                pairWrite(client, JSONObject().put("pending", true).put("nonce", server))
                val nonce = pairRead(input).optString("nonce")
                if (!pairHex(nonce) || !MessageDigest.isEqual(pairHash(nonce).toByteArray(), json.optString("commit").toByteArray()))
                    throw ReceiveFailure("invalid pairing commitment")
                val request = PairRequest(pairNonce().take(32), json.optString("name").filter { !it.isISOControl() }.take(80),
                    pairCompare(fingerprint, nonce, server))
                if (!onPair.invoke(request, ::checkActive)) throw ReceiveFailure("pairing declined or timed out")
                checkActive()
                pairWrite(client, JSONObject().put("ok", true).put("token", token))
                return
            }
            val supplied = json.opt("token") as? String ?: ""
            if (devices != null) {
                if (!devices.authorize(supplied, client)) throw ReceiveFailure("device access revoked; scan Mac QR again")
                deviceToken = supplied
            } else if (!MessageDigest.isEqual(supplied.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8)))
                throw ReceiveFailure("connection code rejected")
            authenticated = true
            stage = "METADATA"
            val name = json.opt("name") as? String ?: throw ReceiveFailure("invalid filename or file too large")
            val sizeValue = json.opt("size")
            if (sizeValue !is Int && sizeValue !is Long) throw ReceiveFailure("invalid filename or file too large")
            val size = (sizeValue as Number).toLong()
            if (!validName(name) || size !in 0..maxBytes) throw ReceiveFailure("invalid filename or file too large")
            val hash = json.opt("sha256") as? String ?: ""
            if (!hash.matches(Regex("[a-fA-F0-9]{64}"))) throw ReceiveFailure("invalid checksum")
            val incoming = IncomingFile(name, size, hash.lowercase())
            stage = "FOLDER"
            store.checkName(name)
            checkActive()
            stage = "CACHE"
            if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) throw ReceiveFailure("cannot create temporary file")
            if (cacheDirectory.usableSpace < size + 1024 * 1024) throw ReceiveFailure("not enough storage")
            snapshot = File.createTempFile("nodi-receive-", ".part", cacheDirectory)
            val digest = MessageDigest.getInstance("SHA-256")
            // Opening and reserving a file before ACK lets disk failures reject the upload early.
            FileOutputStream(snapshot).use { output ->
                active.add(output)
                try {
                    checkActive()
                    stage = "RECEIVE"
                    reply(client, true)
                    client.soTimeout = 30_000
                    onEvent(ReceiveEvent("receiving", name, 0, size))
                    val buffer = ByteArray(64 * 1024)
                    var received = 0L
                    var lastUpdate = 0L
                    while (received < size) {
                        checkActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), size - received).toInt())
                        if (count < 0) throw ReceiveFailure("incomplete transfer")
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        received += count
                        val now = System.nanoTime()
                        if (now - lastUpdate >= 200_000_000L) {
                            onEvent(ReceiveEvent("receiving", name, received, size))
                            lastUpdate = now
                        }
                    }
                    stage = "VERIFY"
                    if (!MessageDigest.isEqual(digest.digest().hex().toByteArray(), incoming.sha256.toByteArray()))
                        throw ReceiveFailure("checksum mismatch; file discarded")
                    checkActive()
                    output.fd.sync()
                } finally { active.remove(output) }
            }
            checkActive()
            onEvent(ReceiveEvent("saving", name, size, size))
            stage = "SAVE"
            savedName = store.commit(snapshot, incoming, ::checkActive)
            // commit is the point of no return. A late disconnect/stop must not erase a saved file.
            onEvent(ReceiveEvent("saved", savedName, size, size))
            stage = "ACK"
            reply(client, true)
        } catch (error: Exception) {
            if (savedName != null) {
                onEvent(ReceiveEvent("saved_unconfirmed", savedName, message = "file saved but acknowledgement failed"))
            } else {
                val reason = (error as? ReceiveFailure)?.reason ?:
                    if (!handshakeComplete) "TLS handshake failed" else "cannot receive or save file"
                if (handshakeComplete) try { reply(client, false, reason) } catch (_: Exception) {}
                val now = System.nanoTime()
                if (!closed.get() && (authenticated || lastUnauthenticatedError == 0L ||
                        now - lastUnauthenticatedError >= 1_000_000_000L)) {
                    if (!authenticated) lastUnauthenticatedError = now
                    onEvent(ReceiveEvent("error", message = reason, diagnostic = receiveDiagnostic(stage, error)))
                }
            }
        } finally {
            devices?.release(client)
            // Bound graceful TLS close as well: a peer may never send close_notify.
            try { client.soTimeout = 1000; client.close() } catch (_: Exception) {}
            deadline.cancel(false)
            snapshot?.delete()
        }
    }

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (bytes.size() < 4096) {
            val b = input.read()
            if (b < 0) throw ReceiveFailure("incomplete header")
            if (b == 10) return String(bytes.toByteArray(), Charsets.UTF_8)
            bytes.write(b)
        }
        throw ReceiveFailure("header too large")
    }
    private fun reply(socket: SSLSocket, ok: Boolean, error: String = "") {
        val json = JSONObject().put("ok", ok)
        if (!ok) json.put("error", error)
        socket.outputStream.write((json.toString() + "\n").toByteArray(Charsets.UTF_8))
        socket.outputStream.flush()
    }
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { listener.close() } catch (_: Exception) {}
        active.forEach { try { if (it is Socket) it.setSoLinger(true, 0); it.close() } catch (_: Exception) {} }
        deadlines.shutdownNow()
    }
    companion object {
        fun validName(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
            name.toByteArray(Charsets.UTF_8).size <= 240 &&
            name.none { it == '/' || it == '\\' || it == ':' || it.code < 32 || it.code == 127 }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }

/** Exception types only: never put raw exception messages, headers or tokens in the UI. */
internal fun receiveDiagnostic(stage: String, error: Throwable): String {
    val types = mutableListOf<String>()
    var current: Throwable? = error
    val visited = mutableSetOf<Throwable>()
    while (current != null && types.size < 4 && visited.add(current)) {
        types.add(current.javaClass.simpleName.take(80))
        current = current.cause
    }
    return stage + ": " + types.joinToString(" → ")
}

private class ServerKeyManager(private val key: PrivateKey, private val certificate: X509Certificate) : X509ExtendedKeyManager() {
    override fun getPrivateKey(alias: String?): PrivateKey? = if (alias == "nodi") key else null
    override fun getCertificateChain(alias: String?): Array<X509Certificate>? = if (alias == "nodi") arrayOf(certificate) else null
    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
        if (keyType == key.algorithm || keyType?.startsWith(key.algorithm + "_") == true) arrayOf("nodi") else null
    override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = getServerAliases(keyType, issuers)?.first()
    override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = getServerAliases(keyType, issuers)?.first()
    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
    override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
}
