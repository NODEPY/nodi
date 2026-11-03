package dev.nodi.android

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.Closeable
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Real loopback TLS connections between production Kotlin and the Go CLI.
 * The filesystem adapter replaces Android SAF only; transport code is unchanged. */
class GoInteropTest {
    @get:Rule val temp = TemporaryFolder()
    private val core get() = System.getProperty("nodi.core")

    private fun await(queue: LinkedBlockingQueue<JSONObject>): JSONObject =
        queue.poll(15, TimeUnit.SECONDS) ?: error("Timed out waiting for Go event")

    private inner class GoReceiver : Closeable {
        val inbox = temp.newFolder()
        val events = LinkedBlockingQueue<JSONObject>()
        private val process = ProcessBuilder(core, "receive", "-json", "-listen", "127.0.0.1:0",
            "-out", inbox.absolutePath).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        private val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { events.put(JSONObject(it)) }
            }
        }.apply { isDaemon = true; start() }
        val code: String
        init {
            val ready = await(events)
            check(ready.getString("type") == "ready")
            code = ready.getJSONArray("invites").getJSONObject(0).getString("code")
        }
        override fun close() {
            process.destroy()
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS))
            }
            reader.join(2000)
        }
    }

    private fun go(command: String, code: String, file: File? = null, text: String = ""): Int {
        val args = mutableListOf(core, command, "-json")
        val input = if (command == "send-text") {
            args.add("-stdin")
            JSONObject().put("code", code).put("text", text).toString()
        } else {
            args.addAll(listOf("-code-stdin", "-file", file!!.absolutePath))
            code
        }
        val output = temp.newFile()
        val process = ProcessBuilder(args).redirectErrorStream(true).redirectOutput(output).start()
        try {
            process.outputStream.bufferedWriter().use { it.write(input) }
            check(process.waitFor(15, TimeUnit.SECONDS)) { "Go sender timed out" }
            return process.exitValue()
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun changed(code: String, field: String): String {
        val json = JSONObject(String(Base64.getUrlDecoder().decode(code.removePrefix("nodi1-")), Charsets.UTF_8))
        val old = json.getString(field)
        json.put(field, (if (old[0] == '0') "1" else "0") + old.drop(1))
        return "nodi1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toString().toByteArray())
    }

    private fun send(code: String, name: String, bytes: ByteArray, transfer: NodiTransfer = NodiTransfer(),
                     progress: (TransferProgress) -> Unit = {}) {
        val cache = temp.newFolder()
        try {
            transfer.send(NodiInvite.parse(code), name, bytes.size.toLong(), cache, { bytes.inputStream() }, progress)
        } finally {
            assertEquals("Upload snapshots must be removed", 0, cache.listFiles()!!.size)
        }
    }

    private inner class KotlinReceiver(trusted: Boolean = false) : Closeable {
        val inbox = temp.newFolder()
        val cache = temp.newFolder()
        val texts = LinkedBlockingQueue<String>()
        val book = DeviceBook(File(temp.newFolder(), "devices.json"))
        val peerPin = "a".repeat(64)
        private val token = "b".repeat(64)
        val receiver: NodiReceiver
        val code: String
        private val worker: Thread
        init {
            // Generate a fresh TLS identity without AndroidKeyStore or committed private keys.
            val keyFile = File(temp.newFolder(), "identity.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool").absolutePath
            val process = ProcessBuilder(keytool, "-genkeypair", "-alias", "test", "-keyalg", "EC",
                "-groupname", "secp256r1", "-dname", "CN=Nodi interop", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", keyFile.absolutePath, "-storepass", "test-password",
                "-noprompt").redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            try {
                check(process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0)
            } finally { if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) } }
            val keys = KeyStore.getInstance("PKCS12")
            keyFile.inputStream().use { keys.load(it, "test-password".toCharArray()) }
            val store = object : ReceiveStore {
                override fun checkName(name: String) {
                    if (File(inbox, name).exists()) throw ReceiveFailure("destination already exists")
                }
                override fun commit(snapshot: File, incoming: IncomingFile, checkActive: () -> Unit): String {
                    checkActive()
                    Files.copy(snapshot.toPath(), File(inbox, incoming.name).toPath())
                    return incoming.name
                }
            }
            receiver = NodiReceiver(keys.getKey("test", "test-password".toCharArray()) as PrivateKey,
                keys.getCertificate("test") as X509Certificate, cache, store, {},
                bindAddress = "127.0.0.1", devices = if (trusted) book else null,
                onText = { texts.put(it) })
            code = if (trusted) {
                book.remember(TrustedDevice("Go", "127.0.0.1:12345", peerPin, token))
                TrustedDevice("Kotlin", "127.0.0.1:${receiver.port}", receiver.fingerprint, token).code()
            } else receiver.invite("127.0.0.1")
            worker = Thread { receiver.run() }.apply { isDaemon = true; start() }
        }
        override fun close() {
            receiver.close()
            worker.join(5000)
            assertFalse("Kotlin receiver did not stop", worker.isAlive)
            assertEquals("Receive snapshots must be removed", 0, cache.listFiles()!!.size)
        }
    }

    @Test(timeout = 60000) fun manualEndpointPreservesTrustForFilesAndText() {
        GoReceiver().use { receiver ->
            val actual = NodiInvite.parse(receiver.code)
            val peer = TrustedDevice("Go", "192.0.2.1:1", actual.fingerprint.hex(), actual.token)
            val endpoint = NodiEndpoint.normalize("127.0.0.1:${actual.port}")
            val code = peer.code(endpoint)
            val routed = NodiInvite.parse(code)
            assertArrayEquals(actual.fingerprint, routed.fingerprint)
            assertEquals(actual.token, routed.token)
            assertEquals("192.0.2.1:1", peer.address)
            send(code, "vpn.bin", byteArrayOf(1, 2, 3))
            assertEquals("received", await(receiver.events).getString("type"))
            assertArrayEquals(byteArrayOf(1, 2, 3), File(receiver.inbox, "vpn.bin").readBytes())
            NodiTransfer().sendText(routed, "manual route")
            assertEquals("manual route", await(receiver.events).getString("text"))
            assertThrows(java.io.IOException::class.java) { send(changed(code, "fingerprint"), "bad.bin", byteArrayOf(4)) }
            assertEquals("error", await(receiver.events).getString("type"))
            assertFalse(File(receiver.inbox, "bad.bin").exists())
        }
    }

    @Test(timeout = 60000) fun kotlinToGoFilesAndNoOverwrite() {
        GoReceiver().use { receiver ->
            for ((name, bytes) in listOf("порожній.txt" to byteArrayOf(),
                "фото 🐈.bin" to ByteArray(1024 * 1024) { (it % 251).toByte() })) {
                send(receiver.code, name, bytes)
                assertEquals("received", await(receiver.events).getString("type"))
                assertArrayEquals(bytes, File(receiver.inbox, name).readBytes())
                assertThrows(ReceiverRejected::class.java) { send(receiver.code, name, byteArrayOf(1)) }
                assertEquals("error", await(receiver.events).getString("type"))
                assertArrayEquals(bytes, File(receiver.inbox, name).readBytes())
            }
        }
    }

    @Test(timeout = 60000) fun goToKotlinFilesAndNoOverwrite() {
        KotlinReceiver().use { receiver ->
            for ((name, bytes) in listOf("порожній.txt" to byteArrayOf(),
                "фото 🐈.bin" to ByteArray(1024 * 1024) { (it % 251).toByte() })) {
                val source = File(temp.newFolder(), name).apply { writeBytes(bytes) }
                assertEquals(0, go("send", receiver.code, source))
                assertArrayEquals(bytes, File(receiver.inbox, name).readBytes())
                source.writeBytes(byteArrayOf(1))
                assertNotEquals(0, go("send", receiver.code, source))
                assertArrayEquals(bytes, File(receiver.inbox, name).readBytes())
            }
        }
    }

    @Test(timeout = 60000) fun textBothWaysIncludingByteLimit() {
        val messages = listOf("Привіт 👋\nhttps://example.com/\tNodi", "я".repeat(32768))
        GoReceiver().use { receiver ->
            for (message in messages) {
                NodiTransfer().sendText(NodiInvite.parse(receiver.code), message)
                val event = await(receiver.events)
                assertEquals("text_received", event.getString("type"))
                assertEquals(message, event.getString("text"))
            }
            assertEquals(0, receiver.inbox.listFiles()!!.size)
        }
        KotlinReceiver().use { receiver ->
            for (message in messages) {
                assertEquals(0, go("send-text", receiver.code, text = message))
                assertEquals(message, receiver.texts.poll(5, TimeUnit.SECONDS))
            }
            assertEquals(0, receiver.inbox.listFiles()!!.size)
        }
    }

    @Test(timeout = 60000) fun invalidCredentialsBothWaysThenReconnect() {
        GoReceiver().use { receiver ->
            for (field in listOf("token", "fingerprint")) {
                assertThrows(java.io.IOException::class.java) { send(changed(receiver.code, field), "bad", byteArrayOf(1)) }
                assertEquals("error", await(receiver.events).getString("type"))
            }
            assertEquals(0, receiver.inbox.listFiles()!!.size)
            send(receiver.code, "good", byteArrayOf(2))
            assertEquals("received", await(receiver.events).getString("type"))
        }
        KotlinReceiver().use { receiver ->
            val source = temp.newFile().apply { writeBytes(byteArrayOf(3)) }
            for (field in listOf("token", "fingerprint"))
                assertNotEquals(0, go("send", changed(receiver.code, field), source))
            assertEquals(0, receiver.inbox.listFiles()!!.size)
            assertEquals(0, go("send", receiver.code, source))
        }
    }

    @Test(timeout = 60000) fun cancelKotlinUploadCleansGoPartialAndAllowsRetry() {
        GoReceiver().use { receiver ->
            val transfer = NodiTransfer()
            assertThrows(CancellationException::class.java) {
                send(receiver.code, "cancelled.bin", ByteArray(1024 * 1024), transfer) {
                    // SENDING is emitted after Go has accepted the header and created its partial file.
                    if (it.phase == TransferPhase.SENDING) transfer.cancel()
                }
            }
            assertEquals("error", await(receiver.events).getString("type"))
            assertEquals(0, receiver.inbox.listFiles()!!.size)
            send(receiver.code, "cancelled.bin", byteArrayOf(7))
            assertEquals("received", await(receiver.events).getString("type"))
            assertArrayEquals(byteArrayOf(7), File(receiver.inbox, "cancelled.bin").readBytes())
        }
    }

    @Test(timeout = 60000) fun forgottenKotlinDeviceRejectsGoFileAndText() {
        KotlinReceiver(trusted = true).use { receiver ->
            assertEquals(0, go("send-text", receiver.code, text = "before"))
            assertEquals("before", receiver.texts.poll(5, TimeUnit.SECONDS))
            assertNotNull(receiver.book.forget(receiver.peerPin))
            val source = temp.newFile().apply { writeText("must not arrive") }
            assertNotEquals(0, go("send", receiver.code, source))
            assertNotEquals(0, go("send-text", receiver.code, text = "after"))
            assertEquals(0, receiver.inbox.listFiles()!!.size)
            assertTrue(receiver.texts.isEmpty())
        }
    }
}
