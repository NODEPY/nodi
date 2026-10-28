package dev.nodi.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PhoneInvite(val address: String, val code: String) {
    override fun toString(): String = address
}
data class ReceiverState(val active: Boolean = false, val stopping: Boolean = false,
    val status: String = "Приймання вимкнене", val detail: String = "",
    val invites: List<PhoneInvite> = emptyList(), val bytes: Long = 0, val total: Long = 0,
    val transferring: Boolean = false, val pairing: PairRequest? = null)

/** Explicitly started, visible foreground receiver. No boot start, no silent restart. */
class NodiReceiveService : Service() {
    companion object {
        const val START = "dev.nodi.android.RECEIVE_START"
        const val STOP = "dev.nodi.android.RECEIVE_STOP"
        const val TREE = "tree"
        private const val CHANNEL = "nodi_receive"
        private const val NOTIFICATION = 7331
        private const val SESSION_MS = 60 * 60 * 1000L
    }
    inner class LocalBinder : Binder() { val service: NodiReceiveService get() = this@NodiReceiveService }
    private class Session {
        val cancelled = AtomicBoolean(false)
        @Volatile var receiver: NodiReceiver? = null
    }
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val observers = mutableSetOf<(ReceiverState) -> Unit>()
    private var session: Session? = null
    private var state = ReceiverState()
    private var wake: PowerManager.WakeLock? = null
    private var advertiser: NodiAdvertiser? = null
    private class Decision(val request: PairRequest) {
        val latch = java.util.concurrent.CountDownLatch(1)
        @Volatile var allow = false
    }
    @Volatile private var decision: Decision? = null
    private var lastPair = 0L
    fun decide(id: String, allow: Boolean) {
        decision?.takeIf { it.request.id == id }?.let { it.allow = allow; it.latch.countDown() }
    }
    private fun awaitPair(current: Session, request: PairRequest, checkActive: () -> Unit): Boolean {
        val now = System.nanoTime()
        if (decision != null || now - lastPair < 3_000_000_000L) return false
        lastPair = now
        val pending = Decision(request); decision = pending
        main.post { if (session === current && !current.cancelled.get()) {
            publish(state.copy(pairing = request))
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("Відкрий Nodi та звір код підключення."))
        } }
        try {
            val end = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < end) {
                checkActive()
                if (current.cancelled.get()) return false
                if (pending.latch.await(250, java.util.concurrent.TimeUnit.MILLISECONDS)) return pending.allow && !current.cancelled.get()
            }
            return false
        } finally {
            decision = null
            main.post { if (session === current) {
                publish(state.copy(pairing = null))
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("Приймання увімкнене."))
            } }
        }
    }
    private val expire = Runnable { stopReceiving("Час приймання минув. За потреби увімкни його знову.") }
    private var stopMessage = "Приймання зупинено"

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, NodiText.text(this,"Приймання файлів Nodi"), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()
    fun observe(listener: (ReceiverState) -> Unit) { observers.add(listener); listener(state) }
    fun removeObserver(listener: (ReceiverState) -> Unit) { observers.remove(listener) }
    private fun publish(value: ReceiverState) { state = value; observers.toList().forEach { it(value) } }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra("show_receive", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, NodiReceiveService::class.java).setAction(STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(NodiText.text(this,"Nodi — приймання файлів"))
            .setContentText(NodiText.text(this,text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, NodiText.text(this,"Зупинити"), stop).build())
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopReceiving(); return START_NOT_STICKY }
        if (intent?.action != START) { stopSelf(startId); return START_NOT_STICKY }
        if (session != null) return START_NOT_STICKY
        // Call before key generation, disk I/O or network setup.
        try {
            startForeground(NOTIFICATION, notification("Запускаю приймач…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } catch (_: Exception) {
            publish(ReceiverState(status = "Android не дозволив запуск приймача", detail = "Відкрий Nodi й повтори запуск. Перевір дозволи застосунку."))
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val tree = intent.getStringExtra(TREE)
        if (tree == null) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId)
            publish(ReceiverState(status = "Спочатку вибери папку"))
            return START_NOT_STICKY
        }
        val current = Session()
        session = current
        stopMessage = "Приймання зупинено"
        publish(ReceiverState(active = true, status = "Запускаю захищений приймач…"))
        main.postDelayed(expire, SESSION_MS)
        worker.execute {
            var failure: String? = null
            var identity: NodiIdentity? = null
            try {
                val addresses = localAddresses()
                if (addresses.isEmpty()) throw ReceiveFailure("no local network")
                NodiDocumentStore.cleanInterrupted(this)
                val store = NodiDocumentStore(this, Uri.parse(tree))
                val temp = File(cacheDir, "receive").apply { mkdirs() }
                // The preceding session has finished; only our own private snapshots are removed.
                temp.listFiles()?.filter { it.name.startsWith("nodi-receive-") && it.name.endsWith(".part") }?.forEach { it.delete() }
                if (current.cancelled.get()) return@execute
                identity = NodiIdentity.create(persistent = true)
                if (current.cancelled.get()) return@execute
                val receiver = NodiReceiver(identity.key, identity.certificate, temp, store, { event ->
                    main.post { if (session === current && !current.cancelled.get()) handleEvent(event) }
                }, devices = NodiDeviceStore.get(this))
                current.receiver = receiver
                if (current.cancelled.get()) { receiver.close(); return@execute }
                val invites = addresses.map { PhoneInvite("$it:${receiver.port}", receiver.invite(it)) }
                val folderName = store.folderName()
                main.post {
                    if (session === current && !current.cancelled.get()) {
                        advertiser?.stop()
                        advertiser = NodiAdvertiser(this, receiver.port, receiver.fingerprint)
                        publish(ReceiverState(active = true, status = "Очікую файли з Mac", invites = invites,
                            detail = "Папка: $folderName. На телефоні скануй QR з Mac один раз. Потім вибирай збережений пристрій. Приймання вимкнеться через годину."))
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification("Очікую файли. Код доступний у Nodi."))
                    }
                }
                receiver.run()
            } catch (error: Exception) {
                if (!current.cancelled.get()) failure = receiveError((error as? ReceiveFailure)?.reason ?: "cannot start receiver")
            } finally {
                current.receiver?.close()
                identity?.close()
                val message = failure
                main.post {
                    if (session === current) {
                        session = null
                        advertiser?.stop(); advertiser = null
                        main.removeCallbacks(expire)
                        releaseWake()
                        publish(ReceiverState(status = message ?: stopMessage,
                            detail = "Для наступної передачі увімкни приймання. Збережені пристрої залишаться підключеними."))
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }
    fun stopReceiving(message: String = "Приймання зупинено") {
        val current = session
        if (current == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return }
        stopMessage = message
        current.cancelled.set(true)
        decision?.latch?.countDown()
        advertiser?.stop(); advertiser = null
        closeNetwork(current)
        releaseWake()
        publish(state.copy(stopping = true, invites = emptyList(), pairing = null, status = "Зупиняю приймання…"))
    }
    private fun handleEvent(event: ReceiveEvent) {
        val transferring = event.phase == "receiving" || event.phase == "saving"
        if (transferring && wake == null) {
            wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Nodi:receive").apply { acquire(31 * 60 * 1000L) }
        } else if (!transferring) releaseWake()
        val status = when (event.phase) {
            "receiving" -> "Отримую файл…"
            "saving" -> "Перевіряю та зберігаю…"
            "saved" -> "Готово — файл на телефоні"
            "saved_unconfirmed" -> "Файл збережено, але Mac не отримав підтвердження"
            else -> "Не вдалося отримати файл"
        }
        val detail = if (event.phase == "error") receiveError(event.message) +
            if (event.diagnostic.isNotEmpty()) "\nКод помилки: ${event.diagnostic}" else ""
            else event.name
        publish(state.copy(status = status, detail = detail, bytes = event.bytes, total = event.total, transferring = transferring))
    }
    private fun releaseWake() {
        wake?.let { if (it.isHeld) it.release() }
        wake = null
    }
    private fun closeNetwork(current: Session) {
        // TLS shutdown may perform socket I/O. Never do it on Android's main thread.
        current.receiver?.let { receiver ->
            Thread({ receiver.close() }, "Nodi-stop-receiver").apply { isDaemon = true; start() }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopReceiving("Android завершив час фонового приймання")
        // The platform requires a prompt stop even if a document provider is slow.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    override fun onDestroy() {
        advertiser?.stop(); advertiser = null
        decision?.latch?.countDown()
        session?.cancelled?.set(true)
        session?.let { closeNetwork(it) }
        main.removeCallbacks(expire)
        releaseWake()
        observers.clear()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun localAddresses(): List<String> {
        val manager = getSystemService(ConnectivityManager::class.java)
        val wifi = manager.allNetworks.flatMap { network ->
            val caps = manager.getNetworkCapabilities(network)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true ||
                caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true)
                manager.getLinkProperties(network)?.linkAddresses?.map { it.address } ?: emptyList()
            else emptyList()
        }.filterIsInstance<Inet4Address>().filter { !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress }
            .mapNotNull { it.hostAddress }
        if (wifi.isNotEmpty()) return wifi.distinct()
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
        return Collections.list(interfaces).filter { it.isUp && !it.isLoopback &&
            (it.name.startsWith("wlan") || it.name.startsWith("swlan") || it.name.startsWith("ap") || it.name.startsWith("eth")) }
            .flatMap { Collections.list(it.inetAddresses) }.filterIsInstance<Inet4Address>()
            .filter { it.isSiteLocalAddress }.mapNotNull { it.hostAddress }.distinct()
    }
}

fun receiveError(reason: String): String = when {
    reason.contains("device access revoked") -> "Доступ пристрою видалено. Для нового підключення скануй QR на Mac."
    reason.contains("TLS handshake") -> "Не вдалося встановити захищене з’єднання. Надішли код помилки нижче."
    reason.contains("connection code rejected") -> "Mac надіслав застарілий або неправильний код. Скопіюй чинний код телефона."
    reason.contains("destination already exists") -> "Файл із таким ім’ям уже є. Перейменуй його на Mac або вибери інший."
    reason.contains("checksum") || reason.contains("verification") -> "Перевірка цілісності не пройшла. Файл не збережено."
    reason.contains("no local network") -> "Не знайдено адресу Wi-Fi. Підключи телефон і Mac до однієї мережі."
    reason.contains("not enough storage") -> "Недостатньо пам’яті. Потрібне місце для тимчасової та збереженої копії."
    reason.contains("folder") || reason.contains("document") || reason.contains("interrupted") -> "Не вдалося записати в папку. Перевір вільне місце та вибери папку ще раз."
    reason.contains("incomplete") -> "Передача обірвалася. Повтори надсилання з Mac."
    reason.contains("filename") -> "Непідтримуване ім’я або завеликий файл. Максимум — 2 GiB."
    reason.contains("cancelled") -> "Передачу зупинено або вичерпано час очікування."
    else -> "Не вдалося прийняти файл або запустити приймач. Перевір Wi-Fi, папку й повтори."
}
