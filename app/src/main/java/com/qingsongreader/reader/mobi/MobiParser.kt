package com.qingsongreader.reader.mobi

import com.qingsongreader.reader.epub.TocEntry
import org.jsoup.Jsoup
import java.io.File
import java.nio.charset.Charset

data class MobiBook(
    val title: String,
    val author: String,
    /** Chapter bodies as HTML fragments. */
    val chapters: List<String>,
    val toc: List<TocEntry>,
    val language: String = ""
)

/**
 * MOBI (KF6/KF7) and AZW3 (KF8) reader.
 *
 * Both are Palm Database (PDB) containers: record 0 carries the PalmDOC +
 * MOBI + EXTH headers, the remaining records carry the book text. KF8 books
 * additionally carry a second MOBI header at the KF8 boundary (EXTH 121) and
 * keep their XHTML flows in the records after it.
 *
 * Supported compression: none (1) and PalmDOC (2). HUFF/CDIC (17480) is
 * rejected with a clear message instead of emitting garbage.
 */
object MobiParser {

    private const val PDB_RECORD_INFO_SIZE = 8

    fun parse(file: File): MobiBook {
        val bytes = file.readBytes()
        if (bytes.size < 78) throw IllegalArgumentException("不是有效的 MOBI/AZW3 文件")
        val type = String(bytes, 60, 4, Charsets.US_ASCII)
        val creator = String(bytes, 64, 4, Charsets.US_ASCII)
        if (type != "BOOK" || (creator != "MOBI" && creator != "TEXT" && creator != "BOOK")) {
            throw IllegalArgumentException("不是 MOBI/AZW3（PDB type=$type creator=$creator）")
        }

        val numRecords = u16(bytes, 76)
        val offsets = IntArray(numRecords + 1)
        for (i in 0 until numRecords) {
            offsets[i] = u32(bytes, 78 + i * PDB_RECORD_INFO_SIZE).toInt()
        }
        offsets[numRecords] = bytes.size

        fun recordAt(index: Int): ByteArray {
            val start = offsets[index]
            val end = offsets[index + 1].coerceAtLeast(start)
            return bytes.copyOfRange(start, end)
        }

        val header = recordAt(0)
        val compression = u16(header, 0)
        val textLength = u32(header, 4)
        val textRecordCount = u16(header, 8)

        val mobiHeaderLength = u32(header, 20)
        val textEncoding = u32(header, 28)
        val fileVersion = u32(header, 36)
        val fullNameOffset = u32(header, 84)
        val fullNameLength = u32(header, 88)
        val exthFlags = u32(header, 16 + 112)
        val extraDataFlags = if (mobiHeaderLength >= 0xE4 + 4) u32(header, 16 + 0xE0) else 0

        val exth = if (exthFlags and 0x40L != 0L) parseExth(header, (16 + mobiHeaderLength).toInt()) else emptyMap()

        if (compression == 17480) {
            throw UnsupportedOperationException(
                "这本书使用了 HUFF/CDIC 压缩，暂不支持。请用 Calibre 转成 EPUB 或重新生成无压缩版本。"
            )
        }

        val title = exth[503]
            ?: readFullName(header, fullNameOffset.toInt(), fullNameLength.toInt())
            ?: file.nameWithoutExtension
        val author = exth[100].orEmpty()
        val language = exth[524].orEmpty()

        val kf8Boundary = exth[121]?.toIntOrNull()
        val isKf8 = (kf8Boundary != null && kf8Boundary > 0 && kf8Boundary < numRecords) || fileVersion >= 8

        val charset = when (textEncoding) {
            65001L -> Charsets.UTF_8
            1252L -> Charset.forName("windows-1252")
            1251L -> Charset.forName("windows-1251")
            932L -> Charset.forName("Shift_JIS")
            else -> Charsets.UTF_8
        }

        val raw = StringBuilder()
        if (isKf8 && kf8Boundary != null && kf8Boundary > 0) {
            // KF8: text resources live after the second MOBI header. Binary
            // resources (fonts, images) are interleaved, so each record is
            // filtered by "does this look like markup?".
            val last = (kf8Boundary + 1 + (u16(recordAt(kf8Boundary), 8))).coerceAtMost(numRecords)
            for (index in (kf8Boundary + 1) until numRecords) {
                if (index >= (if (last > kf8Boundary) last else numRecords)) break
                val rec = recordAt(index)
                val body = stripTrailing(rec, extraDataFlags)
                val decoded = decodeRecord(body, compression, charset)
                if (looksLikeMarkup(decoded)) raw.append(decoded)
            }
            if (raw.isEmpty()) {
                for (index in (kf8Boundary + 1) until numRecords) {
                    val rec = recordAt(index)
                    val decoded = decodeRecord(rec, compression, charset)
                    if (looksLikeMarkup(decoded)) raw.append(decoded)
                }
            }
        } else {
            val count = textRecordCount.coerceAtMost(numRecords - 1)
            for (i in 1..count) {
                val rec = recordAt(i)
                val body = stripTrailing(rec, extraDataFlags)
                raw.append(decodeRecord(body, compression, charset))
            }
        }

        if (raw.isEmpty()) throw IllegalStateException("没有解析出正文内容")

        // Clean up: keep the body markup, drop scripts/styles.
        val document = Jsoup.parse(raw.toString())
        document.select("script, style").forEach { it.remove() }
        val bodyHtml = document.body().html()
        val (chapters, toc) = splitChapters(bodyHtml)

        return MobiBook(
            title = title,
            author = author,
            chapters = chapters,
            toc = toc,
            language = language
        )
    }

    // ------------------------------------------------------------------ PDB

    private fun u16(bytes: ByteArray, offset: Int): Int {
        if (offset + 2 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        if (offset + 4 > bytes.size) return 0L
        var value = 0L
        for (i in 0..3) value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
        return value
    }

    private fun readFullName(header: ByteArray, offset: Int, length: Int): String? {
        if (length <= 0 || offset <= 0 || offset + length > header.size) return null
        val name = String(header, offset, length, Charsets.UTF_8).trim()
        return name.takeIf { it.isNotBlank() }
    }

    private fun parseExth(header: ByteArray, start: Int): Map<Int, String> {
        if (start + 12 > header.size) return emptyMap()
        if (String(header, start, 4, Charsets.US_ASCII) != "EXTH") return emptyMap()
        val count = u32(header, start + 8).toInt()
        val map = HashMap<Int, String>()
        var cursor = start + 12
        repeat(count.coerceAtMost(2000)) {
            if (cursor + 8 > header.size) return map
            val type = u32(header, cursor).toInt()
            val length = u32(header, cursor + 4).toInt()
            if (length < 8 || cursor + length > header.size) return map
            val data = String(header, cursor + 8, length - 8, Charsets.UTF_8).trim()
            if (type in 100..599 && data.isNotBlank()) map[type] = data
            cursor += length
        }
        return map
    }

    /**
     * Removes the trailing "extra data" bytes some records carry (KF8 uses
     * them for resource metadata). Ported from the standard PDB/MOBI recipe.
     */
    private fun stripTrailing(record: ByteArray, flags: Long): ByteArray {
        if (flags == 0L || record.isEmpty()) return record
        var extra = 0
        var testFlags = flags shr 1
        while (testFlags != 0L) {
            if (testFlags and 1L == 1L) {
                extra += trailingEntrySize(record, record.size - extra)
            }
            testFlags = testFlags shr 1
        }
        val end = (record.size - extra).coerceIn(0, record.size)
        return record.copyOfRange(0, end)
    }

    private fun trailingEntrySize(record: ByteArray, size: Int): Int {
        var bitPos = 0
        var result = 0
        var position = size
        while (true) {
            if (position <= 0) return result
            val value = record[position - 1].toInt() and 0xFF
            result = result or ((value and 0x7F) shl bitPos)
            bitPos += 7
            position -= 1
            if (value and 0x80 != 0 || bitPos >= 28 || position == 0) return result
        }
    }

    private fun decodeRecord(record: ByteArray, compression: Int, charset: Charset): String {
        val bytes = when (compression) {
            1 -> record
            2 -> unpackPalmdoc(record)
            17480 -> throw UnsupportedOperationException("HUFF/CDIC 压缩暂不支持")
            else -> record
        }
        return String(bytes, charset)
    }

    /** PalmDOC (LZ77-ish) decompression. */
    private fun unpackPalmdoc(data: ByteArray): ByteArray {
        var out = ByteArray((data.size * 4).coerceAtLeast(64))
        var len = 0
        fun ensure(extra: Int) {
            if (len + extra > out.size) {
                out = out.copyOf(maxOf(out.size * 2, len + extra))
            }
        }
        var i = 0
        while (i < data.size) {
            val b = data[i].toInt() and 0xFF
            i++
            when {
                b == 0 -> {
                    ensure(1)
                    out[len++] = 0
                }
                b <= 8 -> {
                    ensure(b)
                    repeat(b) {
                        if (i < data.size) {
                            out[len++] = data[i]
                            i++
                        }
                    }
                }
                b <= 0x7F -> {
                    ensure(1)
                    out[len++] = b.toByte()
                }
                b <= 0xBF -> {
                    if (i >= data.size) break
                    val second = data[i].toInt() and 0xFF
                    i++
                    // Drop the two tag bits ('10'), keep 14 bits:
                    // 11 bits of distance + 3 bits of length (length = n + 3).
                    val code = ((b and 0x3F) shl 8) or second
                    val length = (code and 0x07) + 3
                    val distance = code shr 3
                    if (distance == 0 || distance > len) break
                    ensure(length)
                    repeat(length) {
                        out[len] = out[len - distance]
                        len++
                    }
                }
                else -> {
                    ensure(2)
                    out[len++] = ' '.code.toByte()
                    out[len++] = (b and 0x7F).toByte()
                }
            }
        }
        return out.copyOf(len)
    }

    /** Heuristic used to skip binary resources interleaved with KF8 flows. */
    private fun looksLikeMarkup(text: String): Boolean {
        if (text.length < 16) return false
        val sample = text.take(4096)
        var printable = 0
        for (ch in sample) {
            when {
                ch == '\n' || ch == '\r' || ch == '\t' -> printable++
                ch.code in 0x20..0x7E -> printable++
                ch.code > 0x7F -> printable++
            }
        }
        val ratio = printable.toFloat() / sample.length.toFloat()
        val hasTag = sample.contains("<") && sample.contains(">")
        return ratio > 0.9f && (hasTag || sample.contains("<?xml"))
    }

    /** Splits the flat markup into chapters on top-level headings. */
    private fun splitChapters(html: String): Pair<List<String>, List<TocEntry>> {
        val pattern = Regex("<(h1|h2|h3)\\b[^>]*>(.*?)</\\1>", RegexOption.IGNORE_CASE)
        val matches = pattern.findAll(html).toList()
        if (matches.size < 2) return listOf(html) to emptyList()

        val chapters = ArrayList<String>()
        val toc = ArrayList<TocEntry>()

        val head = html.substring(0, matches.first().range.first)
        if (head.trim().length > 400) {
            toc.add(TocEntry("开头", "#0"))
            chapters.add(head)
        }

        matches.forEachIndexed { index, match ->
            val start = match.range.first
            val end = if (index + 1 < matches.size) matches[index + 1].range.first else html.length
            val title = Jsoup.parse(match.groupValues[2]).text()
                .trim()
                .ifBlank { "第 ${chapters.size + 1} 节" }
            toc.add(TocEntry(title, "#${chapters.size}"))
            chapters.add(html.substring(start, end))
        }
        return chapters to toc
    }
}
