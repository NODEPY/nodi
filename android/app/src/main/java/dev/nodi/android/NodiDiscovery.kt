package dev.nodi.android

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.InetAddress

private const val SERVICE = "_nodi._tcp."
data class NearbyDevice(val name: String, val host: InetAddress, val port: Int, val pin: String) {
    override fun toString() = "$name · ${host.hostAddress}"
}
/** Public TXT only. The session's upload capability never enters mDNS. */
class NodiAdvertiser(context: Context, listenPort: Int, pin: String) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var stopped = false
    private var registered = false
    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { main.post { registered = true; if (stopped) unregister() } }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { main.post { registered = false } }
        override fun onServiceUnregistered(info: NsdServiceInfo) { main.post { registered = false } }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) { main.post { registered = false } }
    }
    init {
        val info = NsdServiceInfo().apply {
            serviceName = "Nodi ${android.os.Build.MODEL}".take(45)
            serviceType = SERVICE
            this.port = listenPort
            setAttribute("v", "1")
            setAttribute("fp", pin)
        }
        try { manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (_: Exception) { stopped = true }
    }
    private fun unregister() { if (registered) { registered = false; try { manager.unregisterService(listener) } catch (_: Exception) {} } }
    fun stop() { stopped = true; unregister() }
}
/** Per-visible-screen discovery. Late callbacks are ignored after stop. */
@Suppress("DEPRECATION")
class NodiDiscovery(context: Context, private val update: (List<NearbyDevice>) -> Unit, private val failure: () -> Unit) {
    private val manager = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var lock: WifiManager.MulticastLock? = null
    private var running = false
    private var registered = false
    private var resolving = false
    private val found = linkedMapOf<String, NsdServiceInfo>()
    private val devices = linkedMapOf<String, NearbyDevice>()
    private val queue = java.util.ArrayDeque<NsdServiceInfo>()
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) { main.post { registered = true; if (!running) stopRegistered() } }
        override fun onDiscoveryStopped(type: String) { main.post { registered = false } }
        override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if (running) { stop(); failure() } } }
        override fun onStopDiscoveryFailed(type: String, code: Int) { main.post { registered = false } }
        override fun onServiceFound(info: NsdServiceInfo) { main.post {
            if (running && found.size < 32 && info.serviceType.trimEnd('.') == SERVICE.trimEnd('.')) {
                found[info.serviceName] = info
                queue.removeAll { it.serviceName == info.serviceName }; queue.add(info); resolveNext()
            }
        } }
        override fun onServiceLost(info: NsdServiceInfo) { main.post {
            found.remove(info.serviceName); devices.remove(info.serviceName); queue.removeAll { it.serviceName == info.serviceName }
            if (running) update(devices.values.toList())
        } }
    }
    fun start() {
        if (running) return
        running = true
        try {
            lock = wifi?.createMulticastLock("Nodi-discovery")?.apply { setReferenceCounted(false); acquire() }
            manager.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (_: Exception) { stop(); failure() }
    }
    private fun stopRegistered() { if (registered) { registered = false; try { manager.stopServiceDiscovery(listener) } catch (_: Exception) {} } }
    fun stop() {
        running = false; stopRegistered()
        lock?.let { if (it.isHeld) it.release() }; lock = null
        found.clear(); devices.clear(); queue.clear()
    }
    private fun resolveNext() {
        if (!running || resolving || queue.isEmpty()) return
        val next = queue.removeFirst(); resolving = true
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, code: Int) { main.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(info: NsdServiceInfo) { main.post {
                resolving = false
                if (running && found[next.serviceName] === next) {
                    val pin = info.attributes["fp"]?.toString(Charsets.US_ASCII) ?: ""
                    val v = info.attributes["v"]?.toString(Charsets.US_ASCII)
                    val host = info.host
                    if (v == "1" && pairHex(pin) && host is Inet4Address && !host.isLoopbackAddress && !host.isAnyLocalAddress && info.port in 1..65535) {
                        devices[next.serviceName] = NearbyDevice(info.serviceName.filter { !it.isISOControl() }.take(80), host, info.port, pin)
                        update(devices.values.toList())
                    }
                }
                resolveNext()
            } }
        }
        try { manager.resolveService(next, callback) } catch (_: Exception) { resolving = false; resolveNext() }
    }
}
