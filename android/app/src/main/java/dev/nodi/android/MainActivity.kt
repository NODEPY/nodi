package dev.nodi.android

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.os.PersistableBundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.ApiException
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import javax.net.ssl.SSLHandshakeException

class MainActivity : Activity() {
    private companion object {
        const val PICK_FILE = 301
        const val PICK_FOLDER = 401
        const val RECEIVE_NETWORK_PERMISSION = 402
        const val RECEIVE_NOTIFICATION_PERMISSION = 403
        const val NETWORK_PERMISSION_REQUEST = 302
        const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
        val BACKGROUND = Color.rgb(16, 18, 23)
        val CARD = Color.rgb(25, 28, 35)
        val TEXT = Color.rgb(237, 242, 248)
        val MUTED = Color.rgb(151, 158, 174)
        val ACCENT = Color.rgb(160, 177, 255)
    }
    private data class SelectedFile(val uri: Uri, val name: String, val size: Long)
    private val worker = Executors.newSingleThreadExecutor()
    private var selected: List<SelectedFile> = emptyList()
    @Volatile private var batchCancelled=false
    @Volatile private var transfer: NodiTransfer? = null
    private var alive = true
    private var busy = false
    private var selecting = false
    private var scanning = false
    private var pairing = false
    private var pairClient: NodiLink? = null
    private lateinit var deviceBook: DeviceBook
    private lateinit var forgetButton: Button
    private var scanAfterReady = false
    private val devicesChanged: () -> Unit = { ui { renderNearby() } }
    private var discovery: NodiDiscovery? = null
    private var nearby: List<NearbyDevice> = emptyList()
    private var nearbyAll: List<NearbyDevice> = emptyList()
    private var nearbyTitles: List<String> = emptyList()
    private lateinit var nearbyPicker: Spinner
    private lateinit var nearbyConnect: Button
    private lateinit var nearbyRefresh: Button
    private lateinit var nearbyHint: TextView
                private var qrDialog: android.app.AlertDialog? = null
    private var lastPairID: String? = null
    private lateinit var code: EditText
    private lateinit var fileLabel: TextView
    private lateinit var status: TextView
    private lateinit var detail: TextView
    private lateinit var progress: ProgressBar
    private lateinit var pickButton: Button
    private lateinit var pasteButton: Button
    private lateinit var scanButton: Button
    private lateinit var sendButton: Button
    private lateinit var cancelButton: Button

    private var receiverService: NodiReceiveService? = null
    private var bindRequested = false
    private var receiverState = ReceiverState()
    private var receiverInvites: List<PhoneInvite> = emptyList()
    private var choosingFolder = false
    private var folderTree: Uri? = null
    private var showingReceive = false
    private lateinit var sendTab: Button
    private lateinit var receiveTab: Button
    private lateinit var sendColumn: LinearLayout
    private lateinit var receiveColumn: LinearLayout
    private lateinit var receiverFolder: TextView
    private lateinit var receiverStatus: TextView
    private lateinit var receiverDetail: TextView
    private lateinit var receiverCode: TextView
    private lateinit var receiverAddress: Spinner
    private lateinit var receiverPick: Button
    private lateinit var receiverToggle: Button
    private lateinit var receiverCopy: Button
    private lateinit var receiverProgress: ProgressBar
    private val receiveObserver: (ReceiverState) -> Unit = { value ->
        receiverState = value
        renderReceiver()
        renderNearby()
        animateTransfer()
        if(value.transferring)previewKey=""
        if(value.status.startsWith("Готово —") && value.detail != previewKey){previewKey=value.detail;receivedPreview(value.detail,value.total)}
        if (scanAfterReady && value.active && !value.stopping && value.invites.isNotEmpty()) {
            scanAfterReady = false
            scanInvite()
        }
    }
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            receiverService = (binder as NodiReceiveService.LocalBinder).service
            receiverService?.observe(receiveObserver)
            if (scanAfterReady && !receiverState.active && folderTree != null) requestReceive()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            receiverService = null
            receiverState = ReceiverState(status = "Приймач завершився. Увімкни його знову.")
            renderReceiver()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(savedInstanceState)
        deviceBook = NodiDeviceStore.get(this)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        val savedTree = getSharedPreferences("nodi_receive", MODE_PRIVATE).getString("tree", null)
        folderTree = savedTree?.let { Uri.parse(it) }
        showingReceive = savedInstanceState?.getBoolean("show_receive")
            ?: intent.getBooleanExtra("show_receive", false)
        previewKey=savedInstanceState?.getString("preview_key") ?: ""
        buildScreen()
        if(!uiPrefs.getBoolean("setup",false)) setup(true)
        // Only old temporary snapshots owned by this app are cleaned after a crash.
        worker.execute {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            cacheDir.listFiles()?.filter { it.name.startsWith("nodi-upload-") && it.name.endsWith(".part") && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        }
    }
    private fun brandMark() = android.widget.ImageView(this).apply {
        contentDescription="Nodi";scaleType=android.widget.ImageView.ScaleType.FIT_CENTER
        assets.open("NodiMark.png").use {setImageBitmap(android.graphics.BitmapFactory.decodeStream(it))}
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun panelBackground(color: Int): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(14).toFloat()
    }
    private fun label(value: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false): TextView =
        object : TextView(this) { override fun setText(value: CharSequence?, type: BufferType?) { super.setText(NodiText.text(context,value),type) } }.apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    private fun button(title: String, primary: Boolean = false, action: () -> Unit): Button = object : Button(this) { override fun setText(value: CharSequence?, type: TextView.BufferType?) { super.setText(NodiText.text(context,value),type) } }.apply {
        text = title
        isAllCaps = false
        textSize = 14f
        minHeight = 0; minimumHeight = 0; minWidth = 0; minimumWidth = 0
        stateListAnimator = null; elevation = 0f
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        gravity = Gravity.CENTER; setPadding(dp(12), 0, dp(12), 0)
        setTextColor(if (primary) BACKGROUND else TEXT)
        background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(Color.argb(35,255,255,255)), panelBackground(if (primary) ACCENT else CARD), null)
        setOnClickListener { action() }
    }
    private fun icon(button: Button, kind: String, tint: Int) {
        val drawable = object : android.graphics.drawable.Drawable() {
            private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = android.graphics.Paint.Style.STROKE; strokeWidth = 1.8f; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND }
            override fun draw(canvas: android.graphics.Canvas) {
                canvas.save(); canvas.translate(bounds.left.toFloat(),bounds.top.toFloat()); canvas.scale(bounds.width()/24f,bounds.height()/24f)
                when(kind) {
                    "wifi" -> { canvas.drawArc(2f,4f,22f,24f,225f,90f,false,paint); canvas.drawArc(6f,9f,18f,23f,225f,90f,false,paint); canvas.drawCircle(12f,19f,0.8f,paint) }
                    "qr" -> { for ((x,y) in listOf(3f to 3f,15f to 3f,3f to 15f)) canvas.drawRect(x,y,x+6,y+6,paint); canvas.drawLine(15f,15f,21f,15f,paint); canvas.drawLine(15f,15f,15f,21f,paint); canvas.drawLine(19f,19f,21f,21f,paint) }
                    "settings" -> { canvas.drawCircle(12f,12f,7f,paint); canvas.drawCircle(12f,12f,2.5f,paint); for(i in 0..7){canvas.save();canvas.rotate(i*45f,12f,12f);canvas.drawLine(12f,2f,12f,5f,paint);canvas.restore()} }
                    "trash" -> { canvas.drawLine(4f,6f,20f,6f,paint); canvas.drawRect(7f,6f,17f,21f,paint); canvas.drawLine(9f,3f,15f,3f,paint) }
                    "send" -> { canvas.drawLine(12f,20f,12f,4f,paint); canvas.drawLine(6f,10f,12f,4f,paint); canvas.drawLine(18f,10f,12f,4f,paint) }
                    else -> { canvas.drawRoundRect(5f,3f,19f,21f,2f,2f,paint); canvas.drawLine(9f,10f,15f,10f,paint); canvas.drawLine(9f,14f,15f,14f,paint) }
                }; canvas.restore()
            }
            override fun setAlpha(alpha: Int) { paint.alpha=alpha }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter=filter }
            @Deprecated("Android Drawable API") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
        }
        drawable.setBounds(0,0,dp(20),dp(20))
        if(button.text.isEmpty()) { button.setPadding(dp(14),0,dp(14),0);button.compoundDrawablePadding=0 }
        else button.compoundDrawablePadding=dp(8)
        button.setCompoundDrawables(drawable,null,null,null)
    }
    private fun LinearLayout.addItem(view: View, top: Int = 12, height: Int = LinearLayout.LayoutParams.WRAP_CONTENT) {
        addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height).apply { topMargin = dp(top) })
    }
    private fun buildScreen() {
        // Insets belong to the fixed outer container, not the scrolling content.
        // This prevents content from scrolling underneath the status bar.
        val root = FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }; screenRoot=root
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = true
        }
        root.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(24))
        }
        scroll.addView(column)
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val safe = insets.getInsets(WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            if (Build.VERSION.SDK_INT >= 30) WindowInsets.CONSUMED else {
                @Suppress("DEPRECATION")
                insets.consumeSystemWindowInsets()
            }
        }
        val header = LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        header.addView(brandMark(),LinearLayout.LayoutParams(dp(40),dp(40)).apply {rightMargin=dp(10)})
        header.addView(label("nodi",24f,TEXT,true).apply {gravity=Gravity.CENTER_VERTICAL},LinearLayout.LayoutParams(0,dp(44),1f))
        val settings=button(""){setup(false)}.apply { contentDescription=tr("Параметри") }
        icon(settings,"settings",MUTED)
        header.addView(settings,LinearLayout.LayoutParams(dp(44),dp(44)))
        column.addItem(header,0)
        column.addItem(label("Файли поруч",13f,MUTED),0)
        nearbyRefresh = button("Знайти у Wi-Fi", true) { startDiscovery(true) }
        icon(nearbyRefresh, "wifi", BACKGROUND)
        column.addItem(nearbyRefresh, 18, dp(48))
        val deviceCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = panelBackground(CARD)
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        deviceCard.addItem(label("ПРИСТРОЇ", 11f, MUTED, true), 0)
        nearbyHint = label("Пристроїв ще немає", 13f, MUTED)
        deviceCard.addItem(nearbyHint, 8)
        nearbyPicker = Spinner(this).apply { backgroundTintList=android.content.res.ColorStateList.valueOf(MUTED) }
        nearbyPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { fillSelectedDevice() }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        deviceCard.addItem(nearbyPicker, 4, dp(48))
        nearbyConnect = button("Вибрати") { pairNearby() }.apply { visibility = View.GONE }
        deviceCard.addItem(nearbyConnect, 0)
        scanButton = button("Додати пристрій") { scanInvite() }; icon(scanButton,"qr",TEXT)
        forgetButton = button("") { forgetDevice() }.apply { contentDescription=tr("Видалити") }; icon(forgetButton, "trash", MUTED)
        val deviceActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        deviceActions.gravity=Gravity.END
        deviceActions.addView(forgetButton, LinearLayout.LayoutParams(dp(48), dp(48)).apply { leftMargin = dp(8) })
        val deviceHeading=deviceCard.getChildAt(0)
        deviceCard.removeView(deviceHeading)
        val deviceHeader=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        deviceHeader.addView(deviceHeading,LinearLayout.LayoutParams(0,dp(44),1f))
        deviceActions.removeView(forgetButton);deviceHeader.addView(forgetButton,LinearLayout.LayoutParams(dp(44),dp(44)))
        deviceCard.addView(deviceHeader,0)
        deviceCard.addItem(scanButton,8,dp(44))
        column.addItem(deviceCard, 16)
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = panelBackground(CARD); setPadding(dp(4),dp(4),dp(4),dp(4)) }
        sendTab = button("Надіслати") { showMode(false) }
        receiveTab = button("Отримати") { showMode(true) }
        modes.addView(sendTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        modes.addView(receiveTab, LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(4) })
        column.addItem(modes, 16)

        sendColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        receiveColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addItem(sendColumn, 0)
        column.addItem(receiveColumn, 0)

        val fileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBackground(CARD)
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }
        fileCard.addItem(label("ФАЙЛ", 11f, MUTED, true), 0)
        fileLabel = label("Вибери файл для передачі", 16f, TEXT)
        fileCard.addItem(fileLabel, 8)
        pickButton = button("Вибрати файл") { chooseFile() }; icon(pickButton, "file", TEXT)
        fileCard.addItem(pickButton, 14, dp(48))
        sendColumn.addItem(fileCard, 14)

        code = EditText(this).apply {
            hint = "Скануй QR або встав код nodi1-…"
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(TEXT)
            setHintTextColor(MUTED)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            minLines = 2
            maxLines = 3
            filters = arrayOf(InputFilter.LengthFilter(4096))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = panelBackground(CARD)
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        sendColumn.addItem(code, 10)
        code.visibility = View.GONE
        pasteButton = button("Вставити з буфера") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                code.setText(clip.getItemAt(0).coerceToText(this).toString().trim())
            } else { status.text = "Буфер обміну порожній." }
        }
        sendColumn.addItem(pasteButton, 10, dp(46))
        pasteButton.visibility = View.GONE
        sendButton = button("Надіслати", true) { requestSend() }; icon(sendButton, "send", BACKGROUND)
        sendColumn.addItem(sendButton, 16, dp(48))
        cancelButton = button("Скасувати") {
            cancelButton.isEnabled = false
            status.text = "Зупиняю передачу…"
            cancelBatch()
            pairClient?.cancel()
        }.apply { visibility = View.GONE }
        sendColumn.addItem(cancelButton, 10, dp(46))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        sendColumn.addItem(progress, 18)
        status = label("Готово до передачі", 12f, MUTED)
        detail = label("", 13f, MUTED)
        sendColumn.addItem(status, 16)
        sendColumn.addItem(detail, 6)
        buildReceiveScreen()
        showMode(showingReceive)
        setContentView(root)
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(0,
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        }
        root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                v.removeOnAttachStateChangeListener(this)
                v.requestApplyInsets()
            }
            override fun onViewDetachedFromWindow(v: View) = Unit
        })
        root.requestApplyInsets()
        renderBusy()
    }
    private fun renderBusy() {
        val disabled = busy || selecting || scanning || pairing
        pickButton.isEnabled = !disabled
        pasteButton.isEnabled = !disabled
        scanButton.isEnabled = !disabled
        code.isEnabled = !disabled
        sendButton.isEnabled = !disabled
        sendButton.alpha = if (disabled) 0.5f else 1f
        nearbyConnect.isEnabled = !disabled && nearby.isNotEmpty()
        forgetButton.isEnabled = !pairing && !selecting && !scanning && nearby.isNotEmpty()
        nearbyRefresh.isEnabled = !disabled
        nearbyPicker.isEnabled = !disabled
        cancelButton.visibility = if (busy || pairing) View.VISIBLE else View.GONE
        cancelButton.isEnabled = busy || pairing
        progress.visibility = if (busy || pairing) View.VISIBLE else View.GONE
        if (busy || pairing) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        animateTransfer()
    }

    private fun scanInvite() {
        if (busy || selecting || scanning || pairing) return
        if (folderTree == null) {
            scanAfterReady = true
            showMode(true)
            receiverDetail.text = "Для зв’язку в обидва боки спочатку вибери папку збереження, потім натисни «Додати пристрій»."
            receiverPick.performClick()
            return
        }
        if (!receiverState.active || receiverState.invites.isEmpty()) {
            scanAfterReady = true
            showMode(true)
            receiverDetail.text = "Запускаю приймання, щоб після сканування працювали обидва напрямки."
            requestReceive()
            return
        }
        scanning = true
        status.text = "Відкриваю сканер…"
        detail.text = "На Mac натисни «Додати телефон — QR»."
        renderBusy()
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom()
                .build()
            GmsBarcodeScanning.getClient(this, options).startScan()
                .addOnSuccessListener { barcode -> ui {
                    scanning = false
                    renderBusy()
                    try {
                        val raw = barcode.rawValue?.trim() ?: throw IllegalArgumentException()
                        val invite = NodiInvite.parse(raw)
                        if (invite.host.isLoopbackAddress) {
                            status.text = "Це код для тесту на Mac"
                            detail.text = "Вибери на Mac адресу Wi-Fi та покажи її QR-код."
                        } else {
                            connectScannedMac(invite)
                        }
                    } catch (_: Exception) {
                        // Never display raw QR contents or parser errors containing credentials.
                        status.text = "Це не чинний код Nodi"
                        detail.text = "На Mac натисни «Додати телефон — QR» і скануй новий код."
                    }
                } }
                .addOnCanceledListener { ui {
                    scanning = false
                    renderBusy()
                    status.text = "Сканування скасовано"
                    detail.text = "Натисни «Додати пристрій», щоб повторити."
                } }
                .addOnFailureListener { error -> ui { scannerFailed(error) } }
        } catch (error: Exception) {
            scannerFailed(error)
        }
    }
    private fun scannerFailed(error: Exception) {
        scanning = false
        renderBusy()
        status.text = "Не вдалося відкрити сканер"
        detail.text = if (error is ApiException && error.statusCode == CommonStatusCodes.CANCELED)
            "Сканування скасовано. Натисни кнопку, щоб спробувати ще раз."
        else "Увімкни інтернет для першого завантаження сканера та перевір оновлення Google Play services. Потім повтори. Для першого підключення потрібен сканер Nodi."
    }
    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_LOCAL_ONLY, true)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, PICK_FILE)
    }
    @Deprecated("Android platform callback; retained for this dependency-free UI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FOLDER) {
            if (resultCode == RESULT_OK) acceptFolder(data) else scanAfterReady = false
            return
        }
        if (requestCode != PICK_FILE || resultCode != RESULT_OK) return
        val uris=(data?.clipData?.let { clip -> (0 until clip.itemCount).map {clip.getItemAt(it).uri} } ?: listOfNotNull(data?.data)).distinct()
        if(uris.isEmpty())return
        selecting = true
        status.text = "Читаю відомості про файл…"
        renderBusy()
        worker.execute {
            try {
                val files=uris.map { uri ->
                var name = "nodi-file"
                var size = -1L
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                    }
                }
                SelectedFile(uri, name, size)
                }
                ui {
                    selected = files
                    fileLabel.text = if(files.size==1)files[0].name else tr("Файлів: ")+files.size
                    fileLabel.contentDescription=files.joinToString("; "){it.name}
                    selecting = false
                    status.text = "Готово до передачі"
                    detail.text = ""
                    renderBusy()
                }
            } catch (error: Exception) {
                ui {
                    selecting = false
                    status.text = "Не вдалося відкрити файл"
                    detail.text = friendlyError(error)
                    renderBusy()
                }
            }
        }
    }
    private fun requestSend() {
        if (busy || selecting || scanning || pairing) return
        if(code.text.isEmpty()){scanInvite();return}
        if (selected.isEmpty()) { status.text = "Спочатку вибери файл."; return }
        if (Build.VERSION.SDK_INT < 29) { status.text = "Потрібен Android 10 або новіший для TLS 1.3."; return }
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(LOCAL_NETWORK), NETWORK_PERMISSION_REQUEST)
            return
        }
        startTransfer()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 404) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startDiscovery(false)
            else nearbyHint.text = "Дозволь доступ до локальної мережі або скористайся QR."
            return
        }
        if (requestCode == RECEIVE_NETWORK_PERMISSION) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestReceive()
            else receiverDetail.text = "Дозволь Nodi доступ до локальної мережі в налаштуваннях телефона."
            return
        }
        if (requestCode == RECEIVE_NOTIFICATION_PERMISSION) {
            // A denial does not block foreground services; Android still exposes them in its task manager.
            launchReceiver()
            return
        }
        if (requestCode == NETWORK_PERMISSION_REQUEST) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startTransfer()
            else {
                status.text = "Потрібен доступ до локальної мережі"
                detail.text = "Дозволь доступ у налаштуваннях дозволів Nodi, щоб підключитися до Mac."
            }
        }
    }
    private fun startTransfer() {
        if (busy || pairing) return
        val files = selected.toList(); if(files.isEmpty())return
        fillSelectedDevice()
        val invite = try {
            NodiInvite.parse(code.text.toString()).also {
                require(deviceBook.list().any { peer -> peer.token == it.token && peer.fingerprint == it.fingerprint.hex() }) { "Підключення видалене. Скануй QR Mac знову." }
                require(!it.host.isLoopbackAddress) { "Це код для тесту на Mac. Скопіюй код під Wi-Fi адресою Mac." }
            }
        } catch (error: Exception) {
            status.text = "Перевір код з’єднання"
            detail.text = friendlyError(error)
            return
        }
        batchCancelled=false
        busy=true;progress.isIndeterminate=true;status.text="Готую файл…";detail.text="";renderBusy()
        worker.execute {
            var sent=0
            var failure:Exception?=null
            try {
                for((index,file) in files.withIndex()) {
                    if(batchCancelled)throw CancellationException()
                    val session=NodiTransfer();transfer=session
                    if(batchCancelled){session.cancel();throw CancellationException()}
                    session.send(invite,file.name,file.size,cacheDir,
                        openSource={contentResolver.openInputStream(file.uri) ?: throw IOException("Не вдалося прочитати вибраний файл.")},
                        onProgress={update -> ui {showProgress(update);detail.text="${index+1}/${files.size} · ${file.name}\n${detail.text}"}})
                    sent++
                }
            } catch(error:Exception){failure=error}
            ui {
                busy=false;transfer=null;renderBusy()
                status.text=when {sent==files.size -> "Готово · файли збережено";batchCancelled || failure is CancellationException -> "Передачу зупинено";else -> "Не вдалося надіслати"}
                detail.text=tr("Надіслано: ")+"$sent/${files.size}"+(if(failure!=null && !batchCancelled)"\n"+friendlyError(failure!!) else "")
            }
        }
    }
    private fun cancelBatch(){batchCancelled=true;transfer?.cancel()}
    private fun showProgress(update: TransferProgress) {
        val known = update.total > 0 && update.phase in listOf(TransferPhase.PREPARING, TransferPhase.SENDING)
        progress.isIndeterminate = !known
        if (known) progress.progress = ((update.bytes * 1000 / update.total).coerceIn(0, 1000)).toInt()
        status.text = when (update.phase) {
            TransferPhase.PREPARING -> "Готую файл і перевіряю цілісність…"
            TransferPhase.CONNECTING -> "Підключаюся до Mac…"
            TransferPhase.SENDING -> "Надсилаю файл…"
            TransferPhase.CONFIRMING -> "Чекаю підтвердження від Mac…"
        }
        detail.text = when (update.phase) {
            TransferPhase.PREPARING, TransferPhase.SENDING -> if (known)
                "${formatBytes(update.bytes)} / ${formatBytes(update.total)}" else formatBytes(update.bytes)
            TransferPhase.CONNECTING -> "Перевіряю сертифікат пристрою."
            TransferPhase.CONFIRMING -> "Байти передано. Mac перевіряє та зберігає файл."
        }
    }
    private fun friendlyError(error: Throwable): String {
        val message = error.message ?: "Невідома помилка."
        return when {
            message.contains("revoked") -> "Доступ видалено на Mac. Видали підключення тут і скануй новий QR."
            error is SSLHandshakeException -> "Не вдалося перевірити Mac. Перезапусти приймання й скопіюй новий код."
            message.contains("destination already exists") -> "На Mac уже є файл із таким ім’ям. Перейменуй файл або вибери інший."
            message.contains("connection code rejected") -> "Mac відхилив код. Скопіюй актуальний код із вікна Nodi."
            message.contains("ECONNREFUSED") || message.contains("Connection refused") -> "Mac не приймає з’єднання. Увімкни приймання й скопіюй новий код."
            message.contains("ENETUNREACH") || message.contains("timed out", true) || message.contains("EHOSTUNREACH") -> "Mac недоступний. Перевір спільний Wi-Fi, мережеву адресу й дозвіл фаєрвола для Nodi."
            message.contains("ENOSPC") -> "На телефоні немає місця для тимчасової копії. Звільни місце або вибери менший файл."
            message.contains("checksum mismatch") -> "Перевірка цілісності не пройшла. Mac відкинув файл."
            message.contains("invalid filename or file too large") -> "Mac відхилив ім’я або розмір файлу. Максимум — 2 GiB."
            else -> message
        }
    }
    private fun showMode(receive: Boolean) {
        showingReceive = receive
        for ((tab, selected) in listOf(sendTab to !receive, receiveTab to receive)) {
            tab.background = panelBackground(if (selected) Color.rgb(46,53,69) else CARD)
            tab.setTextColor(if (selected) TEXT else MUTED)
            tab.isSelected = selected
        }
        sendColumn.visibility = if (receive) View.GONE else View.VISIBLE
        receiveColumn.visibility = if (receive) View.VISIBLE else View.GONE
    }
    private fun buildReceiveScreen() {
        receiverFolder = label(getSharedPreferences("nodi_receive", MODE_PRIVATE).getString("folder_name", null)
            ?: "Папку ще не вибрано", 14f, MUTED)
        receiveColumn.addItem(receiverFolder, 18)
        receiverPick = button("Папка збереження") {
            if (receiverState.active || choosingFolder) return@button
            val picker = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                putExtra(Intent.EXTRA_LOCAL_ONLY, true)
                folderTree?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
            }
            @Suppress("DEPRECATION")
            startActivityForResult(picker, PICK_FOLDER)
        }
        receiveColumn.addItem(receiverPick, 10, dp(48))
        receiverToggle = button("Увімкнути приймання", true) {
            if (receiverState.active) receiverService?.stopReceiving() else requestReceive()
        }
        receiveColumn.addItem(receiverToggle, 18, dp(54))
        receiverAddress = Spinner(this)
        receiverAddress.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                qrDialog?.dismiss(); qrDialog = null
                receiverCode.text = receiverInvites.getOrNull(position)?.code ?: ""
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        receiveColumn.addItem(receiverAddress, 12, dp(46))
        receiverCode = label("", 12f, MUTED).apply {
            typeface = Typeface.MONOSPACE
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            isSaveEnabled = false
        }
        receiveColumn.addItem(receiverCode, 8)
        receiverCopy = button("Копіювати код телефона") {
            val invite = receiverInvites.getOrNull(receiverAddress.selectedItemPosition) ?: return@button
            val clip = ClipData.newPlainText("Nodi", invite.code)
            clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            receiverDetail.text = "Код скопійовано. Передай його на Mac і встав у поле надсилання Nodi."
        }
        receiveColumn.addItem(receiverCopy, 12, dp(48))
        receiverProgress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
        }
        receiveColumn.addItem(receiverProgress, 18)
        receiverStatus = label("Приймання вимкнене", 16f, TEXT, true)
        receiverDetail = label("", 13f, MUTED)
        receiveColumn.addItem(receiverStatus, 16)
        receiveColumn.addItem(receiverDetail, 8)
        renderReceiver()
    }
    private fun renderReceiver() {
        if (!::receiverToggle.isInitialized) return
        val connected = receiverService != null
        receiverToggle.isEnabled = connected && !choosingFolder && !receiverState.stopping
        receiverToggle.text = if (receiverState.active) "Зупинити" else "Увімкнути приймання"
        receiverPick.isEnabled = connected && !receiverState.active && !choosingFolder
        if (receiverInvites != receiverState.invites) {
            qrDialog?.dismiss(); qrDialog = null
            receiverInvites = receiverState.invites
            val values = receiverInvites.map { it.address }
            receiverAddress.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    label(getItem(position) ?: "", 14f, TEXT).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4), dp(8), dp(8), dp(8)) }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    label(getItem(position) ?: "", 16f, Color.BLACK).apply {
                        setBackgroundColor(Color.WHITE); setPadding(dp(12), dp(16), dp(12), dp(16))
                    }
            }
            receiverCode.text = receiverInvites.firstOrNull()?.code ?: ""
        }
        val ready = receiverInvites.isNotEmpty() && !receiverState.stopping
        receiverAddress.visibility = View.GONE
        receiverCode.visibility = View.GONE
        receiverCopy.visibility = View.GONE
        receiverCopy.isEnabled = ready
        receiverProgress.visibility = if (receiverState.transferring) View.VISIBLE else View.GONE
        receiverProgress.isIndeterminate = receiverState.total == 0L || receiverState.status.startsWith("Перевіряю")
        if (receiverState.total > 0) receiverProgress.progress =
            (receiverState.bytes * 1000 / receiverState.total).coerceIn(0, 1000).toInt()
        receiverStatus.text = if (choosingFolder) "Перевіряю папку…" else receiverState.status
        receiverDetail.text = (if (receiverState.status.startsWith("Очікую")) "Лише підключені пристрої · до 1 год" else receiverState.detail) + if (receiverState.transferring && receiverState.total > 0)
            "\n${formatBytes(receiverState.bytes)} / ${formatBytes(receiverState.total)}" else ""
    }
    private fun acceptFolder(data: Intent?) {
        val uri = data?.data ?: return
        if (receiverState.active) return
        try {
            NodiDocumentStore.requireLocalTree(uri)
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            require(flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0 && flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0) {
                "Потрібен доступ на читання та запис у вибрану папку."
            }
            contentResolver.takePersistableUriPermission(uri, flags)
        } catch (error: Exception) {
            receiverDetail.text = error.message ?: "Не вдалося отримати доступ до папки."
            return
        }
        choosingFolder = true
        renderReceiver()
        worker.execute {
            try {
                val name = NodiDocumentStore(this, uri).folderName()
                getSharedPreferences("nodi_receive", MODE_PRIVATE).edit()
                    .putString("tree", uri.toString()).putString("folder_name", "Папка: $name").apply()
                ui {
                    folderTree = uri
                    receiverFolder.text = "Папка: $name"
                    choosingFolder = false
                    renderReceiver()
                    if (scanAfterReady) requestReceive()
                }
            } catch (_: Exception) { ui {
                choosingFolder = false
                renderReceiver()
                receiverDetail.text = "Ця папка недоступна для запису. Вибери іншу папку у внутрішній пам’яті."
            } }
        }
    }
    private fun requestReceive() {
        if (receiverState.active || choosingFolder || receiverService == null) return
        if (folderTree == null) { receiverDetail.text = "Спочатку вибери папку збереження."; return }
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(LOCAL_NETWORK), RECEIVE_NETWORK_PERMISSION)
            return
        }
        val prefs = getSharedPreferences("nodi_receive", MODE_PRIVATE)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_asked", false)) {
            prefs.edit().putBoolean("notification_asked", true).apply()
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), RECEIVE_NOTIFICATION_PERMISSION)
            return
        }
        launchReceiver()
    }
    private fun launchReceiver() {
        val tree = folderTree ?: return
        try {
            startForegroundService(Intent(this, NodiReceiveService::class.java).setAction(NodiReceiveService.START)
                .putExtra(NodiReceiveService.TREE, tree.toString()))
        } catch (_: Exception) {
            receiverDetail.text = "Android не дозволив запуск. Залиш Nodi відкритим і натисни кнопку ще раз."
        }
    }
    override fun onStart() {
        super.onStart()
        if(!uiPrefs.getBoolean("setup",false))return
        deviceBook.observe(devicesChanged)
        startDiscovery(false)
        if (!bindRequested) bindRequested = bindService(Intent(this, NodiReceiveService::class.java), serviceConnection, Context.BIND_AUTO_CREATE)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("show_receive", false)) showMode(true)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("preview_key",previewKey)
        outState.putBoolean("show_receive", showingReceive)
        super.onSaveInstanceState(outState)
    }


    private fun startDiscovery(requestPermission: Boolean) {
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
            nearbyHint.text = "Дозволь пошук у Wi-Fi"
            if (requestPermission) requestPermissions(arrayOf(LOCAL_NETWORK), 404)
            return
        }
        discovery?.stop()
        nearbyAll = emptyList(); renderNearby()
        discovery = NodiDiscovery(this, { values -> nearbyAll = values; renderNearby() }, {
            nearbyHint.text = "Пошук недоступний · спробуй ще раз"
        }).also { it.start() }
    }
    private fun renderNearby() {
        if (!::nearbyPicker.isInitialized) return
        val selectedPin = nearby.getOrNull(nearbyPicker.selectedItemPosition)?.pin
        val stored = deviceBook.list()
        val values = stored.mapNotNull { peer ->
            try {
                val found = nearbyAll.firstOrNull { it.pin == peer.fingerprint }
                val invite = NodiInvite.parse(peer.code())
                NearbyDevice(peer.name, found?.host ?: invite.host, found?.port ?: invite.port, peer.fingerprint)
            } catch (_: Exception) { null }
        }
        val ownPin=runCatching {NodiInvite.parse(receiverState.invites.first().code).fingerprint.hex()}.getOrNull()
        val allValues=(values+nearbyAll.filter {found -> found.pin!=ownPin && stored.none {it.fingerprint==found.pin}}).distinctBy {it.pin}
        val titles = allValues.map { "${it.name} · " + when {stored.none {p -> p.fingerprint==it.pin} -> tr("Потрібне підключення");nearbyAll.any {found -> found.pin==it.pin} -> tr("у мережі");else -> tr("не знайдено")} }
        if (allValues != nearby || titles != nearbyTitles) {
            nearby = allValues; nearbyTitles = titles
            nearbyPicker.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, titles) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    label(getItem(position) ?: "", 14f, TEXT).apply { maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4), dp(8), dp(8), dp(8)) }
                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
                    label(getItem(position) ?: "", 14f, TEXT).apply { setBackgroundColor(CARD); setPadding(dp(12), dp(16), dp(12), dp(16)) }
            }
            allValues.indexOfFirst { it.pin == selectedPin }.takeIf { it >= 0 }?.let { nearbyPicker.setSelection(it) }
        }
        nearbyHint.text = "Пристроїв ще немає"
        nearbyHint.visibility=if(nearby.isEmpty()) View.VISIBLE else View.GONE
        nearbyPicker.visibility = if (nearby.isEmpty()) View.GONE else View.VISIBLE
        forgetButton.visibility = if (nearby.isEmpty()) View.GONE else View.VISIBLE
        if (::sendButton.isInitialized) { fillSelectedDevice(); renderBusy() }
    }
    private fun fillSelectedDevice() {
        if (!::code.isInitialized || busy || pairing) return
        val device = nearby.getOrNull(nearbyPicker.selectedItemPosition)
        val peer = deviceBook.list().firstOrNull { it.fingerprint == device?.pin }
        forgetButton.visibility=if(peer==null)View.GONE else View.VISIBLE
        if(peer==null || device==null){code.setText("");if(device!=null){nearbyHint.text="Скануй новий QR на Mac";nearbyHint.visibility=View.VISIBLE};return}
        val host = device.host.hostAddress ?: return
        val address = if (host.contains(':')) "[$host]:${device.port}" else "$host:${device.port}"
        code.setText(peer.code(address))
    }
    private fun pairNearby() {
        if (busy || selecting || scanning || pairing) return
        fillSelectedDevice(); showMode(false)
        status.text = if (code.text.isEmpty()) "Спочатку скануй QR Mac" else "Підключення збережене"
        detail.text = "Вибери файл і натисни «Надіслати файл»."
    }
    private fun connectScannedMac(qr: NodiInvite) {
        val own = receiverState.invites.firstOrNull()?.let { NodiInvite.parse(it.code) }
        if (own == null || !receiverState.active || receiverState.stopping) {
            showMode(true); receiverDetail.text = "Увімкни приймання та повтори сканування."; return
        }
        val link = NodiLink(); pairClient = link; pairing = true
        showMode(false); status.text = "Зберігаю підключення в обидва боки…"; detail.text = ""; renderBusy()
        worker.execute {
            try {
                val peer = link.connect(qr, own.fingerprint.hex(), own.port, "Nodi ${Build.MODEL}")
                deviceBook.remember(peer)
                ui { status.text = "Mac підключений"; detail.text = "Файли в обидва боки" }
            } catch (_: Exception) { ui {
                status.text = "Не вдалося зберегти підключення"
                detail.text = "На Mac покажи новий QR та повтори. Перевір спільний Wi-Fi і приймання на телефоні."
            } }
            finally { ui { if (pairClient === link) { pairClient = null; pairing = false; renderNearby(); renderBusy() } } }
        }
    }
    private fun forgetDevice() {
        val device = nearby.getOrNull(nearbyPicker.selectedItemPosition) ?: return
        android.app.AlertDialog.Builder(this).setTitle(tr("Видалити ${device.name}?"))
            .setMessage(tr("Збережене підключення й доступ до цього телефона буде видалено. Активна передача перерветься. Для повторного підключення скануй новий QR Mac. Пристрій залишиться у Wi-Fi."))
            .setNegativeButton(tr("Скасувати"), null)
            .setPositiveButton(tr("Видалити")) { _, _ ->
                cancelBatch()
                worker.execute {
                    try { deviceBook.forget(device.pin); ui { renderNearby(); status.text = "Пристрій видалено"; detail.text = "Його старий ключ більше не дозволяє надсилати файли сюди." } }
                    catch (_: Exception) { ui { status.text = "Не вдалося видалити пристрій. Спробуй ще раз." } }
                }
            }.show()
    }

    private val uiPrefs get() = getSharedPreferences("nodi_ui", MODE_PRIVATE)
    private var pulse: android.animation.ObjectAnimator? = null
    private var previewCard: View? = null
    private var previewKey = ""
    private var screenRoot: FrameLayout? = null
    private fun tr(value: String) = NodiText.text(this, value)
    private fun setup(first: Boolean) {
        if (busy || pairing || selecting) return
        val previous=uiPrefs.getString("language","en") ?: "en"
        var language=previous
        val dialog=android.app.Dialog(this,android.R.style.Theme_DeviceDefault_NoActionBar)
        val outer=FrameLayout(this).apply { setBackgroundColor(BACKGROUND) }
        val scroll=ScrollView(this).apply { isFillViewport=true }
        val box=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(24)) }
        scroll.addView(box);outer.addView(scroll)
        val heading=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL }
        heading.addView(label("nodi",26f,TEXT,true),LinearLayout.LayoutParams(0,dp(44),1f))
        if(!first)heading.addView(button("×"){dialog.cancel()}.apply { contentDescription=tr("Закрити") },LinearLayout.LayoutParams(dp(44),dp(44)))
        box.addItem(heading,0)
        val hero=brandMark()
        box.addView(hero,LinearLayout.LayoutParams(dp(72),dp(72)).apply {topMargin=dp(24)})
        val title=label(if(first)"Твої файли. Напряму." else "Параметри",26f,TEXT,true)
        box.addItem(title,16)
        val sub=label("Налаштуй Nodi під себе",14f,MUTED);box.addItem(sub,8)
        val languageLabel=label("Мова",12f,MUTED,true);box.addItem(languageLabel,28)
        val choices=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(6),dp(6),dp(6),dp(6));background=panelBackground(CARD) }
        val languageButtons=mutableListOf<Button>()
        fun repaint(){
            val codes=listOf("en","uk","ru")
            languageButtons.forEachIndexed { i,v ->
                v.text=listOf("English","Українська","Русский")[i]+if(codes[i]==language)"   ✓" else ""
                v.setTextColor(if(codes[i]==language)ACCENT else TEXT)
                v.background=panelBackground(if(codes[i]==language)Color.rgb(39,45,61) else CARD)
            }
        }
        listOf("en","uk","ru").forEachIndexed { i,code ->
            val choice=button(listOf("English","Українська","Русский")[i]){}
            choice.gravity=Gravity.CENTER_VERTICAL or Gravity.START
            choices.addItem(choice,if(i==0)0 else 2,dp(46));languageButtons.add(choice)
        }
        box.addItem(choices,10)
        fun toggle(text:String,checked:Boolean)=android.widget.Switch(this).apply {
            this.text=tr(text);textSize=14f;setTextColor(TEXT);isChecked=checked;minHeight=dp(48);switchPadding=dp(16)
            thumbTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(ACCENT,Color.rgb(151,158,174)))
            trackTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf()),intArrayOf(Color.rgb(67,79,125),Color.rgb(48,53,64)))
        }
        val previews=toggle("Прев’ю файлів",uiPrefs.getBoolean("previews",true))
        val motion=toggle("Менше анімацій",uiPrefs.getBoolean("reduce_motion",false))
        box.addItem(previews,20);box.addItem(motion,0)
        val done=button(if(first)"Продовжити" else "Готово",true){}
        box.addItem(done,24,dp(48))
        languageButtons.forEachIndexed { i,v -> v.setOnClickListener {
            language=listOf("en","uk","ru")[i];uiPrefs.edit().putString("language",language).apply()
            title.text=if(first)"Твої файли. Напряму." else "Параметри";sub.text="Налаштуй Nodi під себе";languageLabel.text="Мова"
            previews.text=tr("Прев’ю файлів");motion.text=tr("Менше анімацій");done.text=if(first)"Продовжити" else "Готово";repaint()
        } }
        repaint()
        done.setOnClickListener {
            uiPrefs.edit().putString("language",language).putBoolean("previews",previews.isChecked).putBoolean("reduce_motion",motion.isChecked).putBoolean("setup",true).commit()
            dialog.setOnCancelListener(null);dialog.dismiss();recreate()
        }
        dialog.setOnCancelListener {uiPrefs.edit().putString("language",previous).apply()}
        dialog.setCancelable(!first);dialog.setContentView(outer);dialog.show()
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT)
            if(Build.VERSION.SDK_INT>=30){setDecorFitsSystemWindows(true);insetsController?.setSystemBarsAppearance(0,WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)}
            @Suppress("DEPRECATION")
            setStatusBarColor(BACKGROUND)
            @Suppress("DEPRECATION")
            setNavigationBarColor(BACKGROUND)
        }
    }
    private fun animateTransfer() {
        val moving=(busy||receiverState.transferring) && !uiPrefs.getBoolean("reduce_motion",false)
        val target=if(receiverState.transferring) receiverProgress else progress
        if(!moving){pulse?.cancel();pulse=null;progress.alpha=1f;receiverProgress.alpha=1f;return}
        if(pulse?.target===target && pulse?.isRunning==true)return
        pulse?.cancel();progress.alpha=1f;receiverProgress.alpha=1f
        pulse=android.animation.ObjectAnimator.ofFloat(target,"alpha",1f,0.4f,1f).apply { duration=1200;repeatCount=android.animation.ValueAnimator.INFINITE;start() }
    }
    private fun receivedPreview(name: String, bytes: Long) {
        if(!uiPrefs.getBoolean("previews",true)||name.isBlank())return
        val root=screenRoot ?: return
        previewCard?.let{root.removeView(it)}
        val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(18),dp(20),dp(16));background=panelBackground(CARD);elevation=dp(16).toFloat() }
        val header=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val image=android.widget.ImageView(this).apply{setImageResource(android.R.drawable.ic_menu_save);scaleType=android.widget.ImageView.ScaleType.CENTER_CROP;contentDescription=tr("Отримано")}
        header.addView(image,LinearLayout.LayoutParams(dp(64),dp(64)))
        val labels=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(16),0,0,0)}
        labels.addItem(label("Отримано",12f,ACCENT,true),0)
        labels.addItem(label(name,16f,TEXT,true).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END},6)
        labels.addItem(label(formatBytes(bytes),12f,MUTED),4)
        header.addView(labels,LinearLayout.LayoutParams(0,-2,1f));card.addItem(header,0)
        val actions=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        var document:Uri?=null
        val open=button("Відкрити",true){document?.let{uri->try{startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri,contentResolver.getType(uri)?:"*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))}catch(_:Exception){android.widget.Toast.makeText(this,tr("Не вдалося відкрити файл"),android.widget.Toast.LENGTH_SHORT).show()}}}.apply{isEnabled=false}
        val close=button("Закрити"){root.removeView(card);if(previewCard===card)previewCard=null}
        actions.addView(open,LinearLayout.LayoutParams(0,dp(46),1f));actions.addView(close,LinearLayout.LayoutParams(0,dp(46),1f).apply{leftMargin=dp(8)});card.addItem(actions,14)
        root.addView(card,FrameLayout.LayoutParams(-1,-2,android.view.Gravity.BOTTOM).apply{setMargins(dp(16),dp(16),dp(16),dp(16))});previewCard=card
        if(!uiPrefs.getBoolean("reduce_motion",false)){card.translationY=dp(90).toFloat();card.alpha=0f;card.animate().translationY(0f).alpha(1f).setDuration(260).start()}
        val tree=folderTree ?: return
        worker.execute {
            var found:Uri?=null;var thumb:android.graphics.Bitmap?=null
            try {
                val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
                contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use { cursor ->
                    while(cursor.moveToNext())if(cursor.getString(1)==name){found=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));break}
                }
                found?.let { uri -> if(contentResolver.getType(uri)?.startsWith("image/")==true) {
                    thumb=android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(contentResolver,uri)){decoder,info,_->
                        val scale=maxOf(info.size.width,info.size.height).toFloat()/192f
                        decoder.setTargetSize(maxOf(1,(info.size.width/scale.coerceAtLeast(1f)).toInt()),maxOf(1,(info.size.height/scale.coerceAtLeast(1f)).toInt()))
                    }
                } }
            }catch(_:Exception){}
            ui { if(previewCard===card){document=found;open.isEnabled=found!=null;thumb?.let{image.setImageBitmap(it)}} }
        }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MiB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KiB", bytes / 1024.0)
        else -> "$bytes B"
    }
    private fun ui(action: () -> Unit) { runOnUiThread { if (alive && !isDestroyed) action() } }
    override fun onStop() {
        pulse?.cancel();pulse=null
        deviceBook.removeObserver(devicesChanged)
        qrDialog?.dismiss(); qrDialog = null
        discovery?.stop(); discovery = null
        pairClient?.cancel()
        receiverService?.removeObserver(receiveObserver)
        receiverService = null
        if (bindRequested) { unbindService(serviceConnection); bindRequested = false }
        // Sending still requires the screen. Receiving lives in the explicit foreground service.
        if (busy) cancelBatch()
        super.onStop()
    }
    override fun onDestroy() {
        alive = false
        pairClient?.cancel()
        cancelBatch()
        worker.shutdownNow()
        super.onDestroy()
    }
}
