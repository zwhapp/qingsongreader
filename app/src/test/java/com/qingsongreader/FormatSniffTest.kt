package com.qingsongreader

import com.qingsongreader.data.db.BookFormat
import com.qingsongreader.data.repo.detectBookFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Regression tests for content-based format detection.
 *
 * These guard the fix for the "EPUB picked through a document URI shows 乱码"
 * bug: system pickers hand out URIs like content://.../document/1000054575 with
 * no file extension, so the extension-based heuristic used to classify real
 * e-books as TXT and render raw ZIP bytes. Detection now reads magic bytes.
 */
class FormatSniffTest {

    /** Minimal but structurally valid EPUB (ZIP) header. */
    private fun epubHeader(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04)) // local file header
        out.write(byteArrayOf(20, 0))          // version needed
        out.write(byteArrayOf(0, 0))           // flags
        out.write(byteArrayOf(0, 0))           // method: stored
        out.write(byteArrayOf(0, 0))           // mod time
        out.write(byteArrayOf(0, 0))           // mod date
        out.write(ByteArray(4))                // crc32 (ignored)
        out.write(be32(28))                    // compressed size
        out.write(be32(28))                    // uncompressed size
        out.write(be16(8))                     // file name length
        out.write(be16(0))                     // extra field length
        out.write("mimetype".toByteArray(Charsets.US_ASCII))
        out.write("application/epub+zip".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(200))              // rest of the container
        return out.toByteArray()
    }

    /** Palm PDB header carrying type BOOK + creator MOBI at offset 60. */
    private fun mobiHeader(exth: ByteArray?): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(60))                       // name + dates + ids
        out.write("BOOKMOBI".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(8))                        // unique id seed + next list id
        out.write(be16(1))                             // record count
        out.write(ByteArray(8))                        // record 0 info entry
        if (exth != null) out.write(exth)
        out.write(ByteArray(200))
        return out.toByteArray()
    }

    private fun exthBlock(includeKf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("EXTH".toByteArray(Charsets.US_ASCII))
        val records = if (includeKf8) 2 else 1
        out.write(be32(records))                       // record count
        out.write(be32(503))                           // type: full name
        val name = "测试书名".toByteArray(Charsets.UTF_8)
        out.write(be32(8 + name.size))
        out.write(name)
        if (includeKf8) {
            out.write(be32(121))                       // type: KF8 boundary
            out.write(be32(8 + 8))
            out.write(be32(0))                         // boundary offset
            out.write(be32(0))                         // boundary length
        }
        return out.toByteArray()
    }

    private fun be16(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())
    private fun be32(v: Int) = byteArrayOf(
        (v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte()
    )

    @Test
    fun detectsEpubHeader() {
        assertEquals(BookFormat.EPUB, detectBookFormat(epubHeader()))
    }

    @Test
    fun detectsPdfHeader() {
        val pdf = "%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1) + ByteArray(64)
        assertEquals(BookFormat.PDF, detectBookFormat(pdf))
    }

    @Test
    fun detectsClassicMobi() {
        assertEquals(BookFormat.MOBI, detectBookFormat(mobiHeader(exthBlock(includeKf8 = false))))
    }

    @Test
    fun detectsMobiWithoutExth() {
        assertEquals(BookFormat.MOBI, detectBookFormat(mobiHeader(null)))
    }

    @Test
    fun detectsAzw3ByKf8Boundary() {
        assertEquals(BookFormat.AZW3, detectBookFormat(mobiHeader(exthBlock(includeKf8 = true))))
    }

    @Test
    fun plainTextIsNotSniffedAsBinary() {
        val text = "第一章 你好世界\n这是纯文本内容。".toByteArray(Charsets.UTF_8)
        assertNull(detectBookFormat(text))
    }

    @Test
    fun zipWithoutMimetypeEntryIsNotEpub() {
        // A generic ZIP whose first entry is not the EPUB "mimetype" stub.
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04))
        out.write(ByteArray(26))
        out.write(be16(4))
        out.write(be16(0))
        out.write("test".toByteArray(Charsets.US_ASCII))
        out.write(ByteArray(200))
        assertNull(detectBookFormat(out.toByteArray()))
    }

    @Test
    fun shortAndEmptyInputsAreNull() {
        assertNull(detectBookFormat(ByteArray(0)))
        assertNull(detectBookFormat("PK".toByteArray(Charsets.US_ASCII)))
    }
}
