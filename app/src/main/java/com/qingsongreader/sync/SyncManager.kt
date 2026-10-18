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
 * or saved to a file.
 */
class SyncManager(private val context: Context) {

    private val db get() = OpenReaderApp.instance.database

    companion object {
        const val FILE_NAME = "openreader-library.json"
    }

    suspend fun exportJson(): String = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("version", 1)
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

    suspend fun importJson(json: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val root = JSONObject(json)
            val books = root.optJSONArray("books") ?: JSONArray()
            var count = 0
            for (i in 0 until books.length()) {
                val item = books.getJSONObject(i)
                db.bookDao().upsert(
                    BookEntity(
                        id = item.optString("id"),
                        title = item.optString("title"),
                        author = item.optString("author"),
                        format = item.optString("format", "EPUB"),
                        filePath = item.optString("filePath"),
                        coverPath = item.optString("coverPath"),
                        addedAt = item.optLong("addedAt", System.currentTimeMillis()),
                        lastReadAt = item.optLong("lastReadAt"),
                        chapterIndex = item.optInt("chapterIndex"),
                        pageIndex = item.optInt("pageIndex"),
                        progress = item.optDouble("progress").toFloat(),
                        totalChars = item.optLong("totalChars")
                    )
                )
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
            count
        }
    }
}

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
