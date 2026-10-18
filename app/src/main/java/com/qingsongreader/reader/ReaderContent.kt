package com.qingsongreader.reader

import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.reader.epub.EpubBook
import com.qingsongreader.reader.epub.TocEntry
import org.jsoup.Jsoup
import java.io.File

sealed class ReaderContent(open val title: String) {
    abstract val chapterCount: Int
    abstract val toc: List<TocEntry>
    abstract fun chapterTitle(index: Int): String
    abstract fun chapterHtml(index: Int, settings: ReaderSettings): String
    abstract fun chapterBaseUrl(index: Int): String
    abstract fun chapterPlainText(index: Int): String
    abstract fun hrefToChapter(href: String): Int?
}

class EpubContent(
    private val baseDir: File,
    val epub: EpubBook
) : ReaderContent(epub.title) {

    override val chapterCount: Int get() = epub.chapters.size

    override val toc: List<TocEntry> get() = epub.toc

    override fun chapterTitle(index: Int): String =
        epub.chapters.getOrNull(index)?.title ?: ""

    override fun chapterHtml(index: Int, settings: ReaderSettings): String {
        val chapter = epub.chapters.getOrNull(index) ?: return ""
        val file = File(baseDir, chapter.href.split('#').first())
        val bodyHtml = runCatching {
            val doc = Jsoup.parse(file.readText(), "UTF-8")
            doc.select("style").forEach { it.remove() }
            doc.body().html()
        }.getOrElse { "<p>${HtmlFactory.escapeHtml(file.readText().take(20000))}</p>" }
        return HtmlFactory.chapterHtml(bodyHtml, chapter.title, settings)
    }

    override fun chapterBaseUrl(index: Int): String {
        val chapter = epub.chapters.getOrNull(index) ?: return "file:///"
        val file = File(baseDir, chapter.href.split('#').first())
        return "file://${file.parentFile?.absolutePath?.trimEnd('/')}/"
    }

    override fun chapterPlainText(index: Int): String {
        val chapter = epub.chapters.getOrNull(index) ?: return ""
        val file = File(baseDir, chapter.href.split('#').first())
        return runCatching { Jsoup.parse(file.readText(), "UTF-8").text() }.getOrDefault("")
    }

    override fun hrefToChapter(href: String): Int {
        if (href.startsWith("http")) return -1
        val target = href.substringBefore('#')
        val index = epub.chapters.indexOfFirst { it.href.substringBefore('#').endsWith(target) }
        return if (index >= 0) index else epub.chapters.indexOfFirst { it.href.substringBefore('#') == target }
    }
}

class TextContent(
    private val file: File,
    private val markdown: Boolean,
    override val title: String
) : ReaderContent(title) {

    override val chapterCount: Int = 1
    override val toc: List<TocEntry> = emptyList()

    private fun raw(): String = runCatching { file.readText() }.getOrDefault("")

    override fun chapterTitle(index: Int): String = title

    override fun chapterHtml(index: Int, settings: ReaderSettings): String {
        val text = raw()
        return if (markdown) HtmlFactory.markdownHtml(text, title, settings)
        else HtmlFactory.plainTextHtml(text, title, settings)
    }

    override fun chapterBaseUrl(index: Int): String = "file:///"

    override fun chapterPlainText(index: Int): String = raw()

    override fun hrefToChapter(href: String): Int? = null
}

/** Book made of plain HTML fragments: used by MOBI / AZW3. */
class HtmlContent(
    private val fragments: List<String>,
    private val chapterTitles: List<String>,
    override val title: String,
    override val toc: List<TocEntry> = emptyList()
) : ReaderContent(title) {

    override val chapterCount: Int get() = fragments.size

    override fun chapterTitle(index: Int): String =
        chapterTitles.getOrNull(index) ?: "第 ${index + 1} 节"

    override fun chapterHtml(index: Int, settings: ReaderSettings): String =
        HtmlFactory.chapterHtml(fragments.getOrNull(index).orEmpty(), chapterTitle(index), settings)

    override fun chapterBaseUrl(index: Int): String = "file:///"

    override fun chapterPlainText(index: Int): String =
        runCatching { Jsoup.parse(fragments.getOrNull(index).orEmpty()).text() }.getOrDefault("")

    override fun hrefToChapter(href: String): Int =
        href.substringAfter('#', "").toIntOrNull() ?: -1
}

class PdfContent(
    val file: File,
    override val title: String,
    val pages: Int
) : ReaderContent(title) {
    override val chapterCount: Int = pages
    override val toc: List<TocEntry> = emptyList()
    override fun chapterTitle(index: Int): String = "第 ${index + 1} 页"
    override fun chapterHtml(index: Int, settings: ReaderSettings): String = ""
    override fun chapterBaseUrl(index: Int): String = "file:///"
    override fun chapterPlainText(index: Int): String = ""
    override fun hrefToChapter(href: String): Int? = null
}
