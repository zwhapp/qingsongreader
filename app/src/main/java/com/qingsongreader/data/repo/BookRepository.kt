package com.qingsongreader.data.repo

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.qingsongreader.OpenReaderApp
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.db.BookFormat
import com.qingsongreader.reader.EpubContent
import com.qingsongreader.reader.HtmlContent
import com.qingsongreader.reader.PdfContent
import com.qingsongreader.reader.ReaderContent
import com.qingsongreader.reader.TextContent
import com.qingsongreader.reader.epub.EpubParser
import com.qingsongreader.reader.epub.TocEntry
import com.qingsongreader.reader.mobi.MobiBook
import com.qingsongreader.reader.mobi.MobiParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

data class SearchHit(
    val chapterIndex: Int,
    val offset: Int,
    val snippet: String
)

class BookRepository(private val context: Context) {

    private val db get() = OpenReaderApp.instance.database

    fun books() = db.bookDao().observeAll()

    suspend fun get(id: String) = db.bookDao().get(id)

    suspend fun save(book: BookEntity) = db.bookDao().upsert(book)

    suspend fun delete(id: String) {
        db.bookDao().delete(id)
        withContext(Dispatchers.IO) {
            File(booksDir(), "$id.epub").delete()
            File(booksDir(), "$id.pdf").delete()
            File(booksDir(), "$id.txt").delete()
            File(booksDir(), "$id.md").delete()
            File(booksDir(), "$id.mobi").delete()
            File(booksDir(), "$id.azw3").delete()
            File(extractedDir(), id).deleteRecursively()
        }
    }

    fun booksDir(): File = File(context.filesDir, "books").apply { mkdirs() }

    fun extractedDir(): File = File(context.filesDir, "extracted").apply { mkdirs() }

    /** Directory holding the unpacked EPUB (images, css, xhtml). */
    fun bookDir(id: String): File = File(extractedDir(), id)

    /**
     * Copies a document picked by the user into app-private storage and parses
     * its metadata. Returns the stored [BookEntity].
     */
    suspend fun import(uri: Uri, suggestedName: String? = null): BookEntity = withContext(Dispatchers.IO) {
        val name = suggestedName ?: uri.lastPathSegment ?: "book"
        val format = formatOf(name)
        val id = UUID.randomUUID().toString()
        val target = File(booksDir(), id + "." + format.name.lowercase())
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        } ?: throw IllegalArgumentException("无法读取所选文件")
        register(id, name, format, target)
    }

    /** Imports a file that is already on disk (cloud downloads, cache files). */
    suspend fun importFile(source: File, displayName: String): BookEntity = withContext(Dispatchers.IO) {
        val format = formatOf(displayName)
        val id = UUID.randomUUID().toString()
        val target = File(booksDir(), id + "." + format.name.lowercase())
        source.copyTo(target, overwrite = true)
        register(id, displayName, format, target)
    }

    private fun formatOf(name: String): BookFormat {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".epub") -> BookFormat.EPUB
            lower.endsWith(".pdf") -> BookFormat.PDF
            lower.endsWith(".mobi") || lower.endsWith(".prc") -> BookFormat.MOBI
            lower.endsWith(".azw3") || lower.endsWith(".azw") || lower.endsWith(".kf8") -> BookFormat.AZW3
            lower.endsWith(".md") || lower.endsWith(".markdown") -> BookFormat.MD
            else -> BookFormat.TXT
        }
    }

    /** Parses metadata for a copied file and stores it. */
    private suspend fun register(
        id: String,
        name: String,
        format: BookFormat,
        target: File
    ): BookEntity {
        var title = name.substringBeforeLast('.').ifBlank { "未命名" }
        var author = ""
        var coverPath = ""
        var totalChars = 0L

        when (format) {
            BookFormat.EPUB -> {
                val dir = bookDir(id)
                EpubParser.extract(target, dir)
                val epub = EpubParser.parse(dir)
                title = epub.title.ifBlank { title }
                author = epub.author
                coverPath = epub.coverPath?.let { File(dir, it).absolutePath }.orEmpty()
                totalChars = epub.chapters.sumOf { chapter ->
                    File(dir, chapter.href.split('#').first()).length()
                } / 3
            }
            BookFormat.MOBI, BookFormat.AZW3 -> {
                val mobi = MobiParser.parse(target)
                title = mobi.title.ifBlank { title }
                author = mobi.author
                writeMobiCache(id, mobi)
                totalChars = mobi.chapters.sumOf { it.length.toLong() } / 3
            }
            BookFormat.PDF -> {
                val pages = pdfPageCount(target)
                totalChars = pages * 1800L
            }
            BookFormat.TXT, BookFormat.MD -> {
                totalChars = target.length() / 3
            }
        }

        val book = BookEntity(
            id = id,
            title = title,
            author = author,
            format = format.name,
            filePath = target.absolutePath,
            coverPath = coverPath,
            totalChars = totalChars
        )
        db.bookDao().upsert(book)
        return book
    }

    private fun pdfPageCount(file: File): Int = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { it.pageCount }
        }
    }.getOrDefault(0)

    // ------------------------------------------------------- MOBI / AZW3 cache

    private fun mobiDir(id: String): File = File(bookDir(id), "mobi").apply { mkdirs() }

    /** Persists the parsed chapters so opening a book later is instant. */
    private fun writeMobiCache(id: String, mobi: MobiBook) {
        val dir = mobiDir(id)
        dir.deleteRecursively()
        dir.mkdirs()
        mobi.chapters.forEachIndexed { index, html ->
            File(dir, "ch_$index.html").writeText(html)
        }
        File(dir, "titles.txt").writeText(
            mobi.toc.joinToString("\n") { it.title.replace('\n', ' ').replace('\r', ' ') }
        )
    }

    private fun readMobiCache(book: BookEntity): HtmlContent? {
        val dir = File(bookDir(book.id), "mobi")
        val files = dir.listFiles()
            ?.filter { it.name.startsWith("ch_") && it.name.endsWith(".html") }
            ?.sortedBy { it.name.removePrefix("ch_").removeSuffix(".html").toIntOrNull() ?: 0 }
        if (files.isNullOrEmpty()) return null
        val titles = File(dir, "titles.txt").takeIf { it.exists() }?.readLines().orEmpty()
        return HtmlContent(
            fragments = files.map { it.readText() },
            chapterTitles = titles,
            title = book.title,
            toc = titles.mapIndexed { index, title -> TocEntry(title, "#$index") }
        )
    }

    /** Builds the reader model for a stored book. */
    fun open(book: BookEntity): ReaderContent {
        val file = File(book.filePath)
        return when (runCatching { BookFormat.valueOf(book.format) }.getOrDefault(BookFormat.TXT)) {
            BookFormat.EPUB -> {
                val dir = bookDir(book.id)
                val epub = if (File(dir, "META-INF/container.xml").exists()) EpubParser.parse(dir) else {
                    EpubParser.extract(file, dir)
                    EpubParser.parse(dir)
                }
                EpubContent(dir, epub)
            }
            BookFormat.MOBI, BookFormat.AZW3 -> {
                val parsed = runCatching { MobiParser.parse(file) }.getOrNull()
                readMobiCache(book)
                    ?: parsed?.let { HtmlContent(it.chapters, it.toc.map { entry -> entry.title }, book.title, it.toc) }
                    ?: throw IllegalStateException("无法解析该 MOBI/AZW3 文件")
            }
            BookFormat.PDF -> PdfContent(file, book.title, pdfPageCount(file))
            BookFormat.MD -> TextContent(file, markdown = true, title = book.title)
            BookFormat.TXT -> TextContent(file, markdown = false, title = book.title)
        }
    }

    /** Full-text search inside one book. */
    suspend fun search(content: ReaderContent, query: String, maxHits: Int = 50): List<SearchHit> =
        withContext(Dispatchers.Default) {
            if (query.isBlank()) return@withContext emptyList()
            val hits = ArrayList<SearchHit>()
            for (index in 0 until content.chapterCount) {
                val text = content.chapterPlainText(index)
                var from = text.indexOf(query, ignoreCase = true)
                while (from >= 0 && hits.size < maxHits) {
                    val start = (from - 30).coerceAtLeast(0)
                    val end = (from + query.length + 40).coerceAtMost(text.length)
                    hits.add(SearchHit(index, from, text.substring(start, end)))
                    from = text.indexOf(query, from + query.length, ignoreCase = true)
                }
                if (hits.size >= maxHits) break
            }
            hits
        }
}
