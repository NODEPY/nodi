package dev.nodi.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArraySet

class TrustedDevice(val name: String, val address: String, val fingerprint: String, val token: String) {
    fun code(endpoint: String = address): String = "nodi1-" + Base64.getUrlEncoder().withoutPadding().encodeToString(
        JSONObject().put("address", endpoint).put("fingerprint", fingerprint).put("token", token).toString().toByteArray(Charsets.UTF_8))
    override fun toString() = name
}
/** App-private, durable capabilities. Each device has its own independently revocable key. */
class DeviceBook(private val file: File) {
    private val lock = Any()
    private var peers: List<TrustedDevice> = emptyList()
    private val active = mutableMapOf<Closeable, String>()
    private val observers = CopyOnWriteArraySet<() -> Unit>()
    init {
        if (file.exists()) {
            require(file.length() <= 65536) { "Device store too large" }
            val list = JSONArray(file.readText())
            require(list.length() <= 32)
            peers = (0 until list.length()).map { i ->
                val p = list.getJSONObject(i)
                TrustedDevice(p.getString("name"), p.getString("address"), p.getString("fingerprint"), p.getString("token")).also { validate(it) }
            }
            require(peers.map { it.fingerprint }.distinct().size == peers.size)
        }
    }
    private fun validate(peer: TrustedDevice) {
        require(pairHex(peer.fingerprint) && pairHex(peer.token) && peer.name.length <= 160)
        NodiInvite.parse(peer.code())
    }
    fun list(): List<TrustedDevice> = synchronized(lock) { peers.toList() }
    fun observe(observer: () -> Unit) { observers.add(observer) }
    fun removeObserver(observer: () -> Unit) { observers.remove(observer) }
    private fun notifyChanged() { observers.forEach { it() } }
    private fun save(next: List<TrustedDevice>) {
        file.parentFile?.mkdirs()
        val list = JSONArray()
        next.forEach { p -> list.put(JSONObject().put("name", p.name).put("address", p.address).put("fingerprint", p.fingerprint).put("token", p.token)) }
        val temp = File.createTempFile(".devices-", ".tmp", file.parentFile)
        try {
            temp.setReadable(false, false); temp.setWritable(false, false)
            temp.setReadable(true, true); temp.setWritable(true, true)
            FileOutputStream(temp).use { it.write(list.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temp.delete() }
    }
    private fun closeConnections(connections: List<Closeable>) {
        if (connections.isEmpty()) return
        // Closing TLS can perform network I/O; never block the activity's main thread.
        Thread({ connections.forEach { try { if (it is Socket) it.setSoLinger(true, 0); it.close() } catch (_: Exception) {} } }, "Nodi-revoke").start()
    }
    fun remember(peer: TrustedDevice) {
        validate(peer)
        val connections = synchronized(lock) {
            val next = peers.filter { it.fingerprint != peer.fingerprint }
            require(next.size < 32 && next.none { it.token == peer.token })
            save(next + peer); peers = next + peer
            active.filterValues { it == peer.fingerprint }.keys.toList().also { it.forEach(active::remove) }
        }
        closeConnections(connections); notifyChanged()
    }
    fun forget(pin: String): TrustedDevice? {
        var removed: TrustedDevice? = null
        val connections = synchronized(lock) {
            removed = peers.firstOrNull { it.fingerprint == pin }
            if (removed == null) return null
            val next = peers.filter { it.fingerprint != pin }; save(next); peers = next
            active.filterValues { it == pin }.keys.toList().also { it.forEach(active::remove) }
        }
        closeConnections(connections); notifyChanged(); return removed
    }
    fun authorize(token: String, connection: Closeable): Boolean = synchronized(lock) {
        val peer = peers.firstOrNull { MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) } ?: return false
        active[connection] = peer.fingerprint; true
    }
    fun release(connection: Closeable) { synchronized(lock) { active.remove(connection) } }
    fun stillAuthorized(token: String): Boolean = synchronized(lock) { peers.any { MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) } }
    fun unlink(token: String): Boolean {
        val pin = synchronized(lock) { peers.firstOrNull { MessageDigest.isEqual(it.token.toByteArray(), token.toByteArray()) }?.fingerprint } ?: return false
        return forget(pin) != null
    }
}
