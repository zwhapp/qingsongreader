package com.qingsongreader.sync

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.qingsongreader.data.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Exposes the user's WebDAV server to the system file picker (SAF), so
 * "从 WebDAV 导入" is the same DocumentsUI flow as importing from a cloud
 * drive: the picker opens inside the WebDAV tree and hands the app a
 * content Uri, which BookRepository.import streams into private storage.
 *
 * Credentials are read once per process from DataStore; the provider only
 * exists when the user configured WebDAV in Settings.
 */
class WebDavDocumentsProvider : DocumentsProvider() {

    companion object {
        const val AUTHORITY = "com.qingsongreader.webdav.documents"
        const val ROOT_DOC_ID = "webdav"

        /** Start the SAF picker directly inside the WebDAV root. */
        fun rootUri(): Uri =
            DocumentsContract.buildDocumentUri(AUTHORITY, ROOT_DOC_ID)

        private val MIME_BY_EXT = mapOf(
            "epub" to "application/epub+zip",
            "pdf" to "application/pdf",
            "mobi" to "application/x-mobipocket-ebook",
            "azw3" to "application/vnd.amazon.mobi8-ebook",
            "txt" to "text/plain",
            "md" to "text/markdown"
        )

        fun mimeFor(name: String): String =
            MIME_BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"
    }

    private var dav: WebDavSync? = null

    private fun client(): WebDavSync? {
        if (dav != null) return dav
        val settings = runBlocking {
            runCatching { SettingsRepository(ctx).settings.first() }.getOrNull()
        } ?: return null
        if (settings.webdavUrl.isBlank()) return null
        return WebDavSync(settings.webdavUrl, settings.webdavUser, settings.webdavPassword)
            .also { dav = it }
    }

    private val ctx: Context get() = getContext()!!

    /** SAF doc ids are prefixed with ROOT_DOC_ID; the server path is what follows. */
    private fun serverPath(documentId: String): String =
        documentId.removePrefix(ROOT_DOC_ID).trimStart('/')

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val roots = MatrixCursor(projectRoots())
        if (client() != null) {
            roots.newRow().apply {
                add(DocumentsContract.Root.COLUMN_ROOT_ID, ROOT_DOC_ID)
                add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, ROOT_DOC_ID)
                add(DocumentsContract.Root.COLUMN_TITLE, "我的 WebDAV")
                add(DocumentsContract.Root.COLUMN_MIME_TYPES, "")
                add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            }
        }
        return roots
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projectDocuments(projection))
        val dav = client() ?: return cursor
        if (documentId == ROOT_DOC_ID) {
            addDirRow(cursor, "WebDAV", ROOT_DOC_ID)
            return cursor
        }
        val name = documentId.substringAfterLast('/')
        val isDir = runBlocking {
            runCatching {
                dav.list(serverPath(documentId).substringBeforeLast('/', "")).getOrNull()
                    ?.any { it.name == name && it.isDir }
            }.getOrNull() ?: false
        }
        if (isDir) {
            addDirRow(cursor, name, documentId)
        } else {
            // Size unknown without a parent listing; 0 keeps SAF happy.
            addFileRow(cursor, name, documentId, 0L)
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(projectDocuments(projection))
        val dav = client()
            ?: throw IllegalStateException("WebDAV 未配置：请先在设置里填写地址与账号")
        val items = runBlocking { dav.list(serverPath(parentDocumentId)) }.getOrElse {
            throw IllegalStateException("无法连接 WebDAV：${it.message}")
        }
        // Depth:1 PROPFIND includes the target collection itself; drop it so
        // the picker doesn't show a phantom self-named folder.
        val selfName = serverPath(parentDocumentId).substringAfterLast('/')
        items.filter { it.name.isNotBlank() && !(it.isDir && it.name == selfName) }.forEach { item ->
            if (item.isDir) {
                addDirRow(cursor, item.name, "$parentDocumentId/${item.name}".removePrefix("//").replace("//", "/"))
            } else {
                addFileRow(cursor, item.name, "$parentDocumentId/${item.name}".removePrefix("//").replace("//", "/"), item.size)
            }
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        documentId.startsWith(parentDocumentId)

    /**
     * DocumentsUI calls this when an intent carries EXTRA_INITIAL_URI (our
     * "从 WebDAV 导入" entry). Without it the launch falls back to the local
     * storage root. Doc ids are hierarchical: webdav/Novel/book.epub.
     */
    override fun findDocumentPath(
        parentDocumentId: String?,
        documentId: String
    ): DocumentsContract.Path {
        val segments = documentId.removePrefix(ROOT_DOC_ID)
            .split('/')
            .filter { it.isNotBlank() }
        val ids = segments.scan(ROOT_DOC_ID) { acc, part -> "$acc/$part" }
        return DocumentsContract.Path(ROOT_DOC_ID, ids)
    }

    /**
     * Downloads happen on an application-lifetime scope, NOT on the caller's
     * binder thread lifecycle: if the picker is dismissed or the user leaves
     * the app, the transfer keeps running in the background and finishes into
     * the cache. A later open of the same document reuses the cached file (or
     * resumes the partial one through an HTTP Range request), so leaving and
     * coming back never restarts from byte zero.
     */
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val downloads = ConcurrentHashMap<String, Deferred<File>>()

    /** Stable cache file for a document (kept across openDocument calls). */
    private fun cachedFile(documentId: String): File {
        val name = documentId.substringAfterLast('/')
        val key = Integer.toHexString(documentId.hashCode())
        return File(ctx.cacheDir, "saf-$key-$name")
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        require(mode == "r") { "只支持读取" }
        val dav = client()
            ?: throw IllegalStateException("WebDAV 未配置：请先在设置里填写地址与账号")
        val file = cachedFile(documentId)
        // Fully downloaded earlier — serve straight from cache.
        if (file.exists() && file.length() > 0L) {
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val part = File(ctx.cacheDir, file.name + ".part")
        val job = downloads.computeIfAbsent(documentId) {
            downloadScope.async {
                dav.downloadFile(serverPath(documentId), part, resume = true).getOrThrow()
                if (file.exists()) file.delete()
                if (!part.renameTo(file)) error("缓存写入失败")
                file
            }
        }
        // Cancelling the picker must not abort the transfer: it finishes in the
        // background and the next attempt picks up the cached result.
        signal?.setOnCancelListener { /* keep downloading */ }
        // Drop the finished job from the map so a failed attempt can be
        // relaunched (and resume from the partial file).
        job.invokeOnCompletion { downloads.remove(documentId, job) }
        val downloaded = try {
            runBlocking { job.await() }
        } catch (e: Throwable) {
            // Keep the partial file so the next attempt resumes via Range.
            throw IllegalStateException("下载失败：${e.message}")
        }
        return ParcelFileDescriptor.open(downloaded, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // ------------------------------------------------------------------ util

    private fun addDirRow(cursor: MatrixCursor, name: String, docId: String) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        }
    }

    private fun addFileRow(cursor: MatrixCursor, name: String, docId: String, size: Long) {
        cursor.newRow().apply {
            add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, docId)
            add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
            add(DocumentsContract.Document.COLUMN_MIME_TYPE, mimeFor(name))
            add(DocumentsContract.Document.COLUMN_SIZE, size)
            add(DocumentsContract.Document.COLUMN_FLAGS, 0)
        }
    }

    private fun projectRoots(): Array<String> = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_MIME_TYPES,
        DocumentsContract.Root.COLUMN_FLAGS
    )

    private fun projectDocuments(projection: Array<out String>?): Array<String> =
        projection?.map { it }?.toTypedArray() ?: arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_FLAGS
        )
}
