package com.qingsongreader

import com.qingsongreader.reader.mobi.MobiParser
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end check that the app really parses MOBI and AZW3 files.
 *
 * The samples in src/test/resources are produced by tools/gen_mobi_samples.py
 * and are structurally valid PDB/MOBI/KF8 containers. We feed them to the exact
 * same [MobiParser] the app uses at runtime, so a green test proves format support.
 */
class MobiSupportTest {

    private fun sample(name: String): File {
        val url = checkNotNull(javaClass.classLoader.getResource(name)) {
            "missing test resource: $name"
        }
        val tmp = File.createTempFile("mobi_", "_$name")
        url.openStream().use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
        tmp.deleteOnExit()
        return tmp
    }

    private fun fullText(book: com.qingsongreader.reader.mobi.MobiBook): String {
        val sb = StringBuilder()
        book.chapters.forEach { sb.append(Jsoup.parse(it).text()).append(" ") }
        return sb.toString()
    }

    @Test
    fun parsesClassicMobi_uncompressedUtf8() {
        val book = MobiParser.parse(sample("test_book.mobi"))
        assertEquals("MOBI 测试用例", book.title)
        assertEquals("OpenReader", book.author)
        assertFalse("chapters should not be empty", book.chapters.isEmpty())
        val text = fullText(book)
        assertTrue("should contain chapter 1 heading", text.contains("第一章"))
        assertTrue("should contain body text", text.contains("OpenReader 应该能正确解析"))
    }

    @Test
    fun parsesClassicMobi_palmdocCompression() {
        // compression=2 exercises the PalmDOC LZ77 decompressor
        val book = MobiParser.parse(sample("test_book_palmdoc.mobi"))
        assertEquals("PalmDOC 用例", book.title)
        val text = fullText(book)
        assertTrue("PalmDOC must restore the English body", text.contains("The quick brown fox jumps over the lazy dog"))
        assertTrue("second paragraph must survive", text.contains("PalmDOC decompression must restore this English text exactly"))
    }

    @Test
    fun parsesAzw3_kf8Flow() {
        val book = MobiParser.parse(sample("test_book.azw3"))
        assertEquals("AZW3 测试用例", book.title)
        assertEquals("OpenReader", book.author)
        assertFalse("chapters should not be empty", book.chapters.isEmpty())
        // two <h1> headings in two flow records => at least two chapters
        assertTrue("KF8 should split into >=2 chapters", book.chapters.size >= 2)
        val text = fullText(book)
        assertTrue("should contain chapter 1", text.contains("第一章"))
        assertTrue("should contain KF8 body", text.contains("KF8 的 XHTML 流式记录"))
    }
}
