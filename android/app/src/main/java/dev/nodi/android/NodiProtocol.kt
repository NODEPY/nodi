package dev.nodi.android

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** Wire-compatible with Nodi's Go TLS receiver. No Android-only imports. */
class NodiInvite private constructor(
    val host: InetAddress,
    val port: Int,
    val token: String,
    val fingerprint: ByteArray
) {
    // Never include the token in a diagnostic message or generated toString().
    companion object {
        fun parse(raw: String): NodiInvite {
            val code = raw.trim()
            require(code.length <= 4096 && code.startsWith("nodi1-")) { "Неправильний код Nodi." }
            val json = try {
                JSONObject(String(Base64.getUrlDecoder().decode(code.substring(6)), Charsets.UTF_8))
            } catch (_: Exception) { throw IllegalArgumentException("Код пошкоджений. Скопіюй його повністю.") }
            val address = json.getString("address")
            val token = json.getString("token")
            val pin = json.getString("fingerprint")
            require(Regex("[a-fA-F0-9]{64}").matches(token) && Regex("[a-fA-F0-9]{64}").matches(pin)) {
                "Неправильні ключі з’єднання."
            }
            val hostString: String
            val portString: String
            if (address.startsWith("[")) {
                val end = address.indexOf("]:")
                require(end > 1) { "Неправильна IPv6-адреса." }
                hostString = address.substring(1, end)
                portString = address.substring(end + 2)
                require(hostString.contains(':')) { "Неправильна IPv6-адреса." }
                require(Regex("[0-9a-fA-F:]+").matches(hostString)) { "Потрібна числова IP-адреса." }
            } else {
                val split = address.split(':')
                require(split.size == 2) { "Неправильна адреса пристрою." }
                hostString = split[0]
                portString = split[1]
                val parts = hostString.split('.')
                require(parts.size == 4 && parts.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 }) {
                    "Потрібна числова IP-адреса."
                }
            }
            val port = portString.toIntOrNull()
            require(port != null && port in 1..65535) { "Неправильний порт." }
            val host = InetAddress.getByName(hostString)
            require(!host.isAnyLocalAddress && !host.isMulticastAddress) { "Цю адресу не можна використати." }
            val fingerprint = ByteArray(32) { pin.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
            return NodiInvite(host, port, token, fingerprint)
        }
    }
}

enum class TransferPhase { PREPARING, CONNECTING, SENDING, CONFIRMING }
data class TransferProgress(val phase: TransferPhase, val bytes: Long = 0, val total: Long = -1)
class ReceiverRejected(message: String) : IOException(message)

/** One instance per upload. cancel() may safely be called from the UI thread. */
class NodiTransfer {
    companion object { const val MAX_BYTES = 2L * 1024 * 1024 * 1024 }
    private val cancelled = AtomicBoolean(false)
    private val timedOut = AtomicBoolean(false)
    private val resources = CopyOnWriteArrayList<Closeable>()
    val isCancelled: Boolean get() = cancelled.get() && !timedOut.get()

    fun cancel() {
        cancelled.set(true)
        resources.forEach { try { it.close() } catch (_: Exception) {} }
    }
    private fun checkActive() {
        if (timedOut.get()) throw IOException("Перевищено час передачі — 30 хвилин.")
        if (cancelled.get()) throw CancellationException("Передачу скасовано.")
    }
    private fun <T : Closeable> track(resource: T): T {
        resources.add(resource)
        if (cancelled.get()) {
            try { resource.close() } catch (_: Exception) {}
            checkActive()
        }
        return resource
    }

    fun send(
        invite: NodiInvite,
        originalName: String,
        expectedSize: Long,
        cacheDirectory: File,
        openSource: () -> InputStream,
        onProgress: (TransferProgress) -> Unit
    ) {
        require(originalName.isNotBlank() && originalName != "." && originalName != ".." &&
            originalName.toByteArray(Charsets.UTF_8).size <= 240 &&
            originalName.none { it == '/' || it == '\\' || it == ':' || Character.isISOControl(it) }) {
            "Непідтримуване ім’я файлу. Перейменуй його без /, \\ та : (до 240 байтів UTF-8)."
        }
        require(expectedSize <= MAX_BYTES) { "Ліміт цієї версії — 2 GiB на файл." }
        checkActive()
        val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "nodi-timeout").apply { isDaemon = true }
        }
        val deadline = scheduler.schedule({ timedOut.set(true); cancel() }, 30, TimeUnit.MINUTES)
        var snapshot: File? = null
        var lastUpdate = 0L
        fun progress(phase: TransferPhase, bytes: Long = 0, total: Long = -1, force: Boolean = false) {
            val now = System.nanoTime()
            if (force || now - lastUpdate > 100_000_000L) {
                lastUpdate = now
                onProgress(TransferProgress(phase, bytes, total))
            }
        }
        try {
            // Snapshot the selected document once: size/hash/data must describe the same bytes.
            // Sources can be non-seekable or can change between reads.
            progress(TransferPhase.PREPARING, total = expectedSize, force = true)
            snapshot = File.createTempFile("nodi-upload-", ".part", cacheDirectory)
            val hash = MessageDigest.getInstance("SHA-256")
            var size = 0L
            val buffer = ByteArray(64 * 1024)
            track(openSource()).use { source ->
                snapshot.outputStream().use { target ->
                    while (true) {
                        checkActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        size += read
                        require(size <= MAX_BYTES) { "Ліміт цієї версії — 2 GiB на файл." }
                        target.write(buffer, 0, read)
                        hash.update(buffer, 0, read)
                        progress(TransferPhase.PREPARING, size, expectedSize)
                    }
                }
            }
            checkActive()
            val checksum = hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            progress(TransferPhase.CONNECTING, force = true)
            val trust = object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    throw CertificateException("Client certificates are not accepted")
                }
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    val cert = chain?.firstOrNull() ?: throw CertificateException("Missing receiver certificate")
                    val actual = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
                    if (!MessageDigest.isEqual(actual, invite.fingerprint)) {
                        throw CertificateException("Receiver certificate fingerprint mismatch")
                    }
                }
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trust), SecureRandom())
            val socket = track(context.socketFactory.createSocket() as SSLSocket)
            socket.use {
                require("TLSv1.3" in socket.supportedProtocols) { "Для цієї версії Nodi потрібен Android 10 або новіший." }
                socket.enabledProtocols = arrayOf("TLSv1.3")
                socket.soTimeout = 30_000
                socket.connect(InetSocketAddress(invite.host, invite.port), 10_000)
                socket.startHandshake() // The pinned TrustManager runs BEFORE any token or file is sent.
                checkActive()
                val output = socket.outputStream
                val input = BufferedInputStream(socket.inputStream)
                val header = JSONObject().put("token", invite.token).put("name", originalName)
                    .put("size", size).put("sha256", checksum).toString().plus("\n").toByteArray(Charsets.UTF_8)
                require(header.size <= 4096) { "Заголовок файлу завеликий." }
                output.write(header)
                output.flush()
                readReply(input)
                progress(TransferPhase.SENDING, 0, size, force = true)
                var sent = 0L
                snapshot.inputStream().use { source ->
                    while (true) {
                        checkActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        sent += read
                        progress(TransferPhase.SENDING, sent, size)
                    }
                }
                output.flush()
                progress(TransferPhase.CONFIRMING, size, size, force = true)
                socket.soTimeout = 120_000
                try { readReply(input) } catch (error: ReceiverRejected) { throw error }
                catch (error: IOException) {
                    throw IOException("Немає підтвердження. Файл міг зберегтися — перевір папку на Mac перед повтором.", error)
                }
                // Returning normally means the Go receiver acknowledged a verified, saved file.
            }
        } catch (error: Exception) {
            checkActive()
            throw error
        } finally {
            deadline.cancel(false)
            scheduler.shutdownNow()
            resources.forEach { try { it.close() } catch (_: Exception) {} }
            resources.clear()
            snapshot?.delete()
        }
    }
    private fun readReply(input: InputStream) {
        val line = ByteArrayOutputStream()
        while (line.size() < 4096) {
            val b = input.read()
            if (b < 0) throw EOFException("Одержувач закрив з’єднання.")
            if (b == 10) {
                val response = JSONObject(String(line.toByteArray(), Charsets.UTF_8))
                if (!response.getBoolean("ok")) throw ReceiverRejected(response.optString("error", "Одержувач відхилив файл."))
                return
            }
            line.write(b)
        }
        throw IOException("Відповідь одержувача завелика.")
    }
}
