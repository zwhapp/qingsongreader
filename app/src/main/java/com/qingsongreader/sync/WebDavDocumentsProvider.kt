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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

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
     * Downloads the document into the cache directory, then serves it as a
     * read-only descriptor. BookRepository.import copies the stream into
     * private storage and the temp file is deleted right after.
     */
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        require(mode == "r") { "只支持读取" }
        val dav = client()
            ?: throw IllegalStateException("WebDAV 未配置：请先在设置里填写地址与账号")
        val name = documentId.substringAfterLast('/')
        val dest = File.createTempFile("saf-", "." + name.substringAfterLast('.', "epub"), ctx.cacheDir)
        runBlocking { dav.downloadFile(serverPath(documentId), dest) }.getOrElse { throw IllegalStateException("下载失败：${it.message}") }
        return ParcelFileDescriptor.open(dest, ParcelFileDescriptor.MODE_READ_ONLY).also {
            signal?.setOnCancelListener { dest.delete() }
        }
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
