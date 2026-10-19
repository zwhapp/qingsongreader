package com.qingsongreader.sync

import android.content.Context
import com.qingsongreader.OpenReaderApp
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.db.BookmarkEntity
import com.qingsongreader.data.db.HighlightEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Exports / imports the whole library state (books + highlights + bookmarks) as
 * a single JSON document, so it can be pushed to the user's own WebDAV server
 * or saved to a file. The book files themselves travel next to it (see
 * [WebDavSync.uploadFile] / [remoteBookName]).
 */
class SyncManager(private val context: Context) {

    private val db get() = OpenReaderApp.instance.database

    companion object {
        const val FILE_NAME = "openreader-library.json"
        const val BOOKS_DIR = "books"
    }

    /** Local directory holding the imported book files. */
    fun booksDir(): java.io.File =
        java.io.File(context.filesDir, "books").apply { mkdirs() }

    /** Expected local path of a book file inside app-private storage. */
    fun localBookFile(book: BookEntity): java.io.File = localBookPath(book.id, book.format)

    private fun localBookPath(id: String, format: String): java.io.File =
        java.io.File(booksDir(), "$id.${format.lowercase()}")

    /** Remote object name for a book file, e.g. "books/<id>.epub". */
    fun remoteBookName(book: BookEntity): String =
        "$BOOKS_DIR/${book.id}.${book.format.lowercase()}"

    /** Every book whose file is still present on disk, paired with the file. */
    suspend fun bookFiles(): List<Pair<BookEntity, java.io.File>> =
        db.bookDao().all().mapNotNull { book ->
            val file = java.io.File(book.filePath).takeIf { it.exists() } ?: localBookFile(book)
                .takeIf { it.exists() }
            file?.let { book to it }
        }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 2)
        root.put("exportedAt", System.currentTimeMillis())

        val booksArray = JSONArray()
        db.bookDao().all().forEach { booksArray.put(it.toJson()) }
        root.put("books", booksArray)

        val highlightsArray = JSONArray()
        db.annotationDao().allHighlights().forEach { highlightsArray.put(it.toJson()) }
        root.put("highlights", highlightsArray)

        val bookmarksArray = JSONArray()
        db.annotationDao().allBookmarks().forEach { bookmarksArray.put(it.toJson()) }
        root.put("bookmarks", bookmarksArray)

        root.toString()
    }

    /**
     * Imports a library snapshot.
     *
     * [ensureFile] is called for every book whose local file is missing; it
     * should try to fetch the file from the sync server and return whether the
     * book is now readable. Books without a readable file are skipped instead
     * of being imported as broken records (which used to crash the reader).
     */
    suspend fun importJson(
        json: String,
        ensureFile: suspend (BookEntity) -> Boolean = { false }
    ): Result<ImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(json)
            val books = root.optJSONArray("books") ?: JSONArray()
            var count = 0
            var skipped = 0
            for (i in 0 until books.length()) {
                val item = books.getJSONObject(i)
                val localPath = localBookPath(
                    item.optString("id"),
                    item.optString("format", "EPUB")
                )
                val coverPath = item.optString("coverPath")
                    .takeIf { it.isNotBlank() && java.io.File(it).exists() }
                    .orEmpty()
                val entity = BookEntity(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    author = item.optString("author"),
                    format = item.optString("format", "EPUB"),
                    // Always point at the local copy: paths from another device
                    // or an older export may not exist here.
                    filePath = localPath.absolutePath,
                    coverPath = coverPath,
                    addedAt = item.optLong("addedAt", System.currentTimeMillis()),
                    lastReadAt = item.optLong("lastReadAt"),
                    chapterIndex = item.optInt("chapterIndex"),
                    pageIndex = item.optInt("pageIndex"),
                    progress = item.optDouble("progress").toFloat(),
                    totalChars = item.optLong("totalChars")
                )
                if (!localPath.exists() && !ensureFile(entity)) {
                    skipped++
                    continue
                }
                db.bookDao().upsert(entity)
                count++
            }

            val highlights = root.optJSONArray("highlights") ?: JSONArray()
            for (i in 0 until highlights.length()) {
                val item = highlights.getJSONObject(i)
                db.annotationDao().upsert(
                    HighlightEntity(
                        id = item.optString("id"),
                        bookId = item.optString("bookId"),
                        chapterIndex = item.optInt("chapterIndex"),
                        startOffset = item.optInt("startOffset"),
                        endOffset = item.optInt("endOffset"),
                        text = item.optString("text"),
                        color = item.optString("color", "#FFE082"),
                        note = item.optString("note"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }

            val bookmarks = root.optJSONArray("bookmarks") ?: JSONArray()
            for (i in 0 until bookmarks.length()) {
                val item = bookmarks.getJSONObject(i)
                db.annotationDao().upsert(
                    BookmarkEntity(
                        id = item.optString("id"),
                        bookId = item.optString("bookId"),
                        chapterIndex = item.optInt("chapterIndex"),
                        offset = item.optInt("offset"),
                        label = item.optString("label"),
                        createdAt = item.optLong("createdAt", System.currentTimeMillis())
                    )
                )
            }
            ImportResult(imported = count, skipped = skipped)
        }
    }
}

/** Outcome of a library import: how many books came back, how many were skipped. */
data class ImportResult(val imported: Int, val skipped: Int)

private fun BookEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("title", title)
    put("author", author)
    put("format", format)
    put("filePath", filePath)
    put("coverPath", coverPath)
    put("addedAt", addedAt)
    put("lastReadAt", lastReadAt)
    put("chapterIndex", chapterIndex)
    put("pageIndex", pageIndex)
    put("progress", progress.toDouble())
    put("totalChars", totalChars)
}

private fun HighlightEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("bookId", bookId)
    put("chapterIndex", chapterIndex)
    put("startOffset", startOffset)
    put("endOffset", endOffset)
    put("text", text)
    put("color", color)
    put("note", note)
    put("createdAt", createdAt)
}

private fun BookmarkEntity.toJson(): JSONObject = JSONObject().apply {
    put("id", id)
    put("bookId", bookId)
    put("chapterIndex", chapterIndex)
    put("offset", offset)
    put("label", label)
    put("createdAt", createdAt)
}
