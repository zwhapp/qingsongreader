package com.qingsongreader.data.repo

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
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
     * its metadata. Returns the stored [BookEntity]. [onProgress] reports the
     * copied byte count and the source size (-1 when unknown).
     */
    suspend fun import(
        uri: Uri,
        suggestedName: String? = null,
        onProgress: (Long, Long) -> Unit = { _, _ -> }
    ): BookEntity = withContext(Dispatchers.IO) {
        val name = queryDisplayName(uri)
            ?: suggestedName?.takeIf { it.contains('.') }
            ?: uri.lastPathSegment
            ?: "book"
        val id = UUID.randomUUID().toString()
        val staging = File(booksDir(), "$id.staging")
        val total = querySize(uri)
        var received = 0L
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(staging).use { output ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    received += read
                    onProgress(received, total)
                }
            }
        } ?: throw IllegalArgumentException("无法读取所选文件")
        // Content sniffing wins over the name: document URIs frequently carry no
        // usable extension (e.g. content://.../document/1000054575), which used
        // to make real EPUB/PDF files fall through to the TXT reader (乱码).
        val format = sniffFormat(staging) ?: formatOf(name)
        val target = File(booksDir(), id + "." + format.name.lowercase())
        staging.renameTo(target)
        register(id, name, format, target)
    }

    /** Imports a file that is already on disk (cloud downloads, cache files). */
    suspend fun importFile(source: File, displayName: String): BookEntity = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val target = File(booksDir(), id + "." + formatOf(displayName).name.lowercase())
        source.copyTo(target, overwrite = true)
        val format = sniffFormat(target) ?: formatOf(displayName)
        val finalTarget = File(booksDir(), id + "." + format.name.lowercase())
        if (finalTarget.absolutePath != target.absolutePath) target.renameTo(finalTarget)
        register(id, displayName, format, finalTarget)
    }

    /** Resolves the user-visible file name behind a document/content URI. */
    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && !cursor.isNull(index)) cursor.getString(index) else null
            } else null
        }
    }.getOrNull()

    /** Source size for progress reporting; -1 when unavailable. */
    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(
            uri, arrayOf(OpenableColumns.SIZE), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else -1L
            } else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    private fun sniffFormat(file: File): BookFormat? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(512)
            val read = input.read(buf)
            if (read <= 0) null else detectBookFormat(buf.copyOf(read))
        }
    }.getOrNull()

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
    suspend fun open(book: BookEntity): ReaderContent {
        var entity = book
        var file = File(book.filePath)
        var format = runCatching { BookFormat.valueOf(book.format) }.getOrDefault(BookFormat.TXT)
        // Self-heal books imported before content sniffing existed: an EPUB/PDF
        // picked through a document URI without extension was stored as TXT and
        // rendered as raw bytes. Detect the real format and fix the record.
        if (format == BookFormat.TXT || format == BookFormat.MD) {
            val sniffed = sniffFormat(file)
            if (sniffed != null) {
                val renamed = File(booksDir(), book.id + "." + sniffed.name.lowercase())
                if (renamed.absolutePath != file.absolutePath && file.renameTo(renamed)) {
                    file = renamed
                    entity = book.copy(format = sniffed.name, filePath = renamed.absolutePath)
                    save(entity)
                }
                format = sniffed
            }
        }
        // A missing file (e.g. the book was deleted, or a sync restore lost the
        // binary) must surface as a readable error, not a raw FileNotFoundException.
        if (!file.exists()) {
            throw IllegalStateException("书籍文件不存在（可能已被删除或同步未完整）")
        }
        return when (format) {
            BookFormat.EPUB -> {
                val dir = bookDir(entity.id)
                val epub = if (File(dir, "META-INF/container.xml").exists()) EpubParser.parse(dir) else {
                    EpubParser.extract(file, dir)
                    EpubParser.parse(dir)
                }
                EpubContent(dir, epub)
            }
            BookFormat.MOBI, BookFormat.AZW3 -> {
                val parsed = runCatching { MobiParser.parse(file) }.getOrNull()
                readMobiCache(entity)
                    ?: parsed?.let { HtmlContent(it.chapters, it.toc.map { entry -> entry.title }, entity.title, it.toc) }
                    ?: throw IllegalStateException("无法解析该 MOBI/AZW3 文件")
            }
            BookFormat.PDF -> PdfContent(file, entity.title, pdfPageCount(file))
            BookFormat.MD -> TextContent(file, markdown = true, title = entity.title)
            BookFormat.TXT -> TextContent(file, markdown = false, title = entity.title)
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

/**
 * Detects a binary book format from the file header. Returns null when the
 * content is not recognisably EPUB / PDF / MOBI / AZW3 (i.e. plain text or
 * markdown, which have no magic bytes).
 *
 * Used as the authoritative source when a picked document has no usable file
 * extension, so real e-books never fall through to the plain-text reader.
 */
internal fun detectBookFormat(bytes: ByteArray): BookFormat? {
    fun at(offset: Int, s: String): Boolean {
        if (offset < 0 || offset + s.length > bytes.size) return false
        for (i in s.indices) {
            if (bytes[offset + i] != s[i].code.toByte()) return false
        }
        return true
    }
    if (bytes.size < 16) return null
    // PDF
    if (at(0, "%PDF-")) return BookFormat.PDF
    // EPUB: ZIP local file header whose first entry is the stored "mimetype"
    if (bytes.size >= 4 &&
        bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() &&
        bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
    ) {
        if (at(30, "mimetypeapplication/epub+zip") || at(30, "mimetype")) {
            return BookFormat.EPUB
        }
    }
    // MOBI / AZW3: Palm PDB header, type "BOOK" + creator "MOBI" at offset 60
    if (at(60, "BOOKMOBI")) {
        // A KF8 boundary (EXTH record type 121) marks AZW3; otherwise classic MOBI
        val exth = "EXTH".toByteArray(Charsets.US_ASCII)
        var i = bytes.indexOf(exth)
        while (i >= 0 && i + 8 <= bytes.size) {
            val count = be32(bytes, i + 4)
            var p = i + 8
            for (r in 0 until count) {
                if (p + 8 > bytes.size) break
                val type = be32(bytes, p)
                val len = be32(bytes, p + 4)
                if (len < 8 || p + len > bytes.size) break
                if (type == 121) return BookFormat.AZW3
                p += len
            }
            i = bytes.indexOf(exth, i + 1)
        }
        return BookFormat.MOBI
    }
    return null
}

private fun be32(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)

private fun ByteArray.indexOf(needle: ByteArray, fromIndex: Int = 0): Int {
    if (needle.isEmpty() || fromIndex < 0 || fromIndex >= size) return -1
    outer@ for (i in fromIndex..size - needle.size) {
        for (j in needle.indices) {
            if (this[i + j] != needle[j]) continue@outer
        }
        return i
    }
    return -1
}
