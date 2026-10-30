package dev.nodi.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File

fun nodiDeviceName(context: Context): String =
    (runCatching { Settings.Global.getString(context.contentResolver, "device_name") }.getOrNull()
        ?.takeIf { it.isNotBlank() } ?: android.os.Build.MODEL).filter { !it.isISOControl() }.take(60)

/** Discovery/pairing can run without granting any write access. */
class NodiConnectionStore : ReceiveStore {
    @Volatile var current: ReceiveStore? = null
    private val transferStore = ThreadLocal<ReceiveStore>()
    override fun checkName(name: String) {
        transferStore.remove()
        val store = current ?: throw ReceiveFailure("receiving disabled; choose a folder and enable receiving on phone")
        store.checkName(name)
        transferStore.set(store)
    }
    override fun commit(snapshot: File, incoming: IncomingFile, checkActive: () -> Unit): String {
        val store = transferStore.get() ?: throw ReceiveFailure("receiving disabled")
        try {
            return store.commit(snapshot, incoming) {
                checkActive()
                if (current !== store) throw ReceiveFailure("receiving disabled")
            }
        } finally { transferStore.remove() }
    }
    companion object {
        fun open(context: Context, tree: Uri): NodiDocumentStore {
            val app = context.applicationContext
            val grant = app.contentResolver.persistedUriPermissions.firstOrNull {
                it.uri == tree && it.isReadPermission && it.isWritePermission
            } ?: throw ReceiveFailure("folder permission missing; select the folder again")
            val access = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            app.contentResolver.takePersistableUriPermission(grant.uri, access)
            app.grantUriPermission(app.packageName, grant.uri, access or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            NodiDocumentStore.cleanInterrupted(app, tree)
            return NodiDocumentStore(app, tree).validateWritable()
        }
    }
}
