package dev.nodi.android

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Only the local filesystem provider is accepted in this version. */
class NodiDocumentStore(context: Context, private val tree: Uri) : ReceiveStore {
    private val appContext = context.applicationContext
    private val resolver = context.applicationContext.contentResolver
    private val journal = context.getSharedPreferences("nodi_receive_pending", Context.MODE_PRIVATE)
    private val parent: Uri
    init {
        requireLocalTree(tree)
        parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val projection = arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS)
        resolver.query(parent, projection, null, null, null)?.use {
            if (!it.moveToFirst() || it.getString(0) != DocumentsContract.Document.MIME_TYPE_DIR ||
                it.getInt(1) and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE == 0)
                throw ReceiveFailure("folder is not writable")
        } ?: throw ReceiveFailure("folder is not writable")
    }
    fun folderName(): String = displayName(parent) ?: "Nodi"

    override fun checkName(name: String) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(0).equals(name, ignoreCase = true)) throw ReceiveFailure("destination already exists")
            }
        } ?: throw ReceiveFailure("cannot read destination folder")
    }

    override fun commit(snapshot: File, incoming: IncomingFile, checkActive: () -> Unit): String {
        checkActive()
        cleanInterrupted(appContext)
        checkName(incoming.name)
        val temporaryName = ".nodi-${UUID.randomUUID()}.part"
        var document = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", temporaryName)
            ?: throw ReceiveFailure("cannot create destination document")
        var committed = false
        try {
            // Keep the exact owned URI/name so a later launch can clean an interrupted copy.
            if (!journal.edit().putString("uri", document.toString()).putString("name", temporaryName).commit())
                throw IOException("Cannot journal destination")
            resolver.query(document, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use {
                val needed = DocumentsContract.Document.FLAG_SUPPORTS_RENAME or DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                if (!it.moveToFirst() || it.getInt(0) and needed != needed)
                    throw ReceiveFailure("folder does not support safe saving")
            } ?: throw ReceiveFailure("cannot read destination document")
            resolver.openOutputStream(document, "w")?.use { output ->
                snapshot.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    output.flush()
                }
            } ?: throw ReceiveFailure("cannot write destination document")
            checkActive()
            // Re-open after closing the writer: confirmation covers destination bytes, not just cache.
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            resolver.openInputStream(document)?.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    size += count
                    if (size > incoming.size) throw ReceiveFailure("saved file verification failed")
                    digest.update(buffer, 0, count)
                }
            } ?: throw ReceiveFailure("cannot verify destination document")
            if (size != incoming.size || digest.digest().hex() != incoming.sha256)
                throw ReceiveFailure("saved file verification failed")
            checkName(incoming.name)
            checkActive()
            // ExternalStorageProvider creates unique names on collisions; it never opens an existing file here.
            document = DocumentsContract.renameDocument(resolver, document, incoming.name)
                ?: throw ReceiveFailure("cannot finalize destination document")
            committed = true
            journal.edit().clear().commit()
            // A metadata lookup failure after rename must not discard a committed file.
            return try { displayName(document) ?: incoming.name } catch (_: Exception) { incoming.name }
        } finally {
            if (!committed) {
                val deleted = try { DocumentsContract.deleteDocument(resolver, document) } catch (_: Exception) { false }
                if (deleted) journal.edit().clear().commit()
                // On cleanup failure, journal survives for the next start; the .part name is never final.
            }
        }
    }

    private fun displayName(uri: Uri): String? = resolver.query(uri,
        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    companion object {
        fun requireLocalTree(uri: Uri) {
            require(uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri)) {
                "Вибери папку у внутрішній пам’яті телефона або на SD-карті. Хмарні папки поки не підтримуються."
            }
        }
        fun cleanInterrupted(context: Context) {
            val prefs = context.getSharedPreferences("nodi_receive_pending", Context.MODE_PRIVATE)
            val raw = prefs.getString("uri", null) ?: return
            val expected = prefs.getString("name", null) ?: return
            if (!expected.startsWith(".nodi-") || !expected.endsWith(".part")) return
            val uri = Uri.parse(raw)
            if (uri.authority != "com.android.externalstorage.documents") return
            try {
                val name = context.contentResolver.query(uri,
                    arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: throw java.io.FileNotFoundException()
                if (name == expected && !DocumentsContract.deleteDocument(context.contentResolver, uri))
                    throw IOException("Cannot remove interrupted document")
                prefs.edit().clear().commit()
            } catch (_: java.io.FileNotFoundException) {
                prefs.edit().clear().commit() // Rename may have changed the URI before process death.
            } catch (_: Exception) {
                throw ReceiveFailure("cannot clean interrupted file; check folder permission")
            }
        }
    }
}
