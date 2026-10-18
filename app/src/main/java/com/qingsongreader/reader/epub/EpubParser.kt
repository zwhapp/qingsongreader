package com.qingsongreader.reader.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

data class EpubChapter(
    val index: Int,
    /** Path relative to the extracted book directory. */
    val href: String,
    val title: String
)

data class TocEntry(
    val title: String,
    /** Path relative to the extracted book directory (anchor kept). */
    val href: String,
    val children: MutableList<TocEntry> = mutableListOf()
)

data class EpubBook(
    val title: String,
    val author: String,
    val language: String,
    val chapters: List<EpubChapter>,
    val toc: List<TocEntry>,
    /** Path of the cover image relative to the extracted directory. */
    val coverPath: String?
)

object EpubParser {

    /** Extracts an .epub (a zip container) into [destDir], guarding against zip-slip. */
    fun extract(epubFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(FileInputStream(epubFile)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val out = safeDestination(destDir, entry.name)
                    out.parentFile?.mkdirs()
                    FileOutputStream(out).use { fos -> zis.copyTo(fos) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun safeDestination(base: File, name: String): File {
        val normalized = File(base, name).canonicalPath
        if (!normalized.startsWith(base.canonicalPath + File.separator)) {
            throw SecurityException("Zip entry outside of destination: $name")
        }
        return File(normalized)
    }

    fun parse(baseDir: File): EpubBook {
        val container = File(baseDir, "META-INF/container.xml")
        val opfRel = when {
            container.exists() -> parseContainer(container)
            else -> findOpf(baseDir)
        } ?: throw IllegalArgumentException("Not a valid EPUB: missing OPF file")

        val opfFile = File(baseDir, opfRel)
        val opf = Jsoup.parse(opfFile.readText(), "", Parser.xmlParser())
        val opfDir = File(opfRel).parent?.let { if (it.isEmpty()) "" else "$it/" } ?: ""

        // ---- manifest: id -> href (namespace agnostic) ----
        val manifest = LinkedHashMap<String, String>()
        val mediaTypes = HashMap<String, String>()
        val properties = HashMap<String, String>()
        childrenNamed(opf, "manifest")
            .flatMap { it.children() }
            .forEach { item ->
                val id = item.attr("id")
                val href = decode(item.attr("href"))
                if (id.isNotBlank() && href.isNotBlank()) {
                    manifest[id] = normalize(opfDir + href)
                    mediaTypes[id] = item.attr("media-type")
                    properties[id] = item.attr("properties")
                }
            }

        // ---- metadata ----
        val metadata = childrenNamed(opf, "metadata")
        val title = metadata.firstNotNullOfOrNull { m ->
            childrenNamed(m, "title").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        } ?: opfFile.parentFile?.name ?: "Untitled"
        val author = metadata.firstNotNullOfOrNull { m ->
            childrenNamed(m, "creator").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
        }.orEmpty()
        val language = metadata.firstNotNullOfOrNull { m ->
            childrenNamed(m, "language").firstOrNull()?.text()
        }.orEmpty()

        // ---- cover ----
        var coverPath: String? = null
        val coverMetaId = metadata.flatMap { it.children() }
            .firstOrNull { it.tagName().substringAfter(':') == "meta" && it.attr("name").equals("cover", true) }
            ?.attr("content")
        if (coverMetaId != null) coverPath = manifest[coverMetaId]
        if (coverPath == null) {
            coverPath = manifest.entries.firstOrNull { (id, href) ->
                properties[id].orEmpty().contains("cover-image") ||
                    (mediaTypes[id].orEmpty().startsWith("image") && href.contains("cover", true))
            }?.value
        }

        // ---- spine ----
        val spine = childrenNamed(opf, "spine").firstOrNull()
        val tocAttr = spine?.attr("toc")
        val chapters = ArrayList<EpubChapter>()
        (spine?.children() ?: emptyList()).forEach { ref ->
            if (ref.tagName().substringAfter(':') != "itemref") return@forEach
            if (ref.attr("linear").equals("no", true)) return@forEach
            val href = manifest[ref.attr("idref")] ?: return@forEach
            val file = File(baseDir, href.split('#').first())
            if (!file.exists()) return@forEach
            chapters.add(EpubChapter(chapters.size, href, guessTitle(file)))
        }
        if (chapters.isEmpty()) {
            manifest.values
                .filter { it.endsWith(".xhtml", true) || it.endsWith(".html", true) || it.endsWith(".htm", true) }
                .filter { File(baseDir, it).exists() }
                .forEach { href -> chapters.add(EpubChapter(chapters.size, href, guessTitle(File(baseDir, href)))) }
        }

        val toc = parseToc(baseDir, opf, manifest, properties, tocAttr)
        return EpubBook(title, author, language, chapters, toc, coverPath)
    }

    private fun parseContainer(container: File): String? {
        val doc = Jsoup.parse(container.readText(), "", Parser.xmlParser())
        return childrenNamed(doc, "rootfile").firstOrNull()?.attr("full-path")?.let { decode(it) }
    }

    private fun findOpf(baseDir: File): String? =
        baseDir.walkTopDown().firstOrNull { it.isFile && it.extension.equals("opf", true) }
            ?.relativeTo(baseDir)?.path?.replace('\\', '/')

    /** EPUB 3 navigation document first, EPUB 2 NCX second. */
    private fun parseToc(
        baseDir: File,
        opf: Document,
        manifest: Map<String, String>,
        properties: Map<String, String>,
        tocAttr: String?
    ): List<TocEntry> {
        val navHref = manifest[tocAttr] ?: manifest.entries.firstOrNull { (id, _) ->
            properties[id].orEmpty().contains("nav")
        }?.value

        if (navHref != null) {
            val navFile = File(baseDir, navHref.split('#').first())
            if (navFile.exists()) {
                val navDoc = Jsoup.parse(navFile.readText(), "UTF-8")
                val navs = navDoc.select("nav")
                val nav = navs.firstOrNull {
                    it.attr("epub:type").equals("toc", true) || it.attr("type").equals("toc", true)
                } ?: navs.firstOrNull()
                val ol = nav?.select("ol")?.firstOrNull()
                if (ol != null) {
                    val base = File(navHref.split('#').first()).parent
                        ?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
                    val list = collectNavList(ol, base)
                    if (list.isNotEmpty()) return list
                }
            }
        }

        val ncxHref = manifest.values.firstOrNull { it.endsWith(".ncx", true) }
        if (ncxHref != null) {
            val ncxFile = File(baseDir, ncxHref)
            if (ncxFile.exists()) {
                val ncx = Jsoup.parse(ncxFile.readText(), "", Parser.xmlParser())
                val base = File(ncxHref).parent?.let { if (it.isEmpty()) "" else "$it/" } ?: ""
                return collectNcx(childrenNamed(ncx, "navMap").firstOrNull(), base)
            }
        }
        return emptyList()
    }

    private fun collectNavList(ol: Element, base: String): List<TocEntry> {
        val result = ArrayList<TocEntry>()
        ol.children().filter { it.tagName() == "li" }.forEach { li ->
            val a = li.select("a").firstOrNull()
            val title = a?.text()?.takeIf { it.isNotBlank() } ?: li.text()
            val rawHref = a?.attr("href")?.takeIf { it.isNotBlank() }
            val href = rawHref?.let { normalize(base + decode(it)) }
            val entry = TocEntry(title, href ?: "")
            val childOl = li.children().firstOrNull { it.tagName() == "ol" }
            if (childOl != null) entry.children.addAll(collectNavList(childOl, base))
            if (href != null) result.add(entry) else result.addAll(entry.children)
        }
        return result
    }

    private fun collectNcx(navMap: Element?, base: String): List<TocEntry> {
        if (navMap == null) return emptyList()
        return navMap.children()
            .filter { it.tagName().substringAfter(':') == "navPoint" }
            .map { convertNavPoint(it, base) }
    }

    private fun convertNavPoint(point: Element, base: String): TocEntry {
        val label = childrenNamed(point, "navLabel").firstOrNull()
        val title = label?.let { childrenNamed(it, "text").firstOrNull()?.text() }.orEmpty()
        val src = childrenNamed(point, "content").firstOrNull()?.attr("src").orEmpty()
        val entry = TocEntry(title, if (src.isBlank()) "" else normalize(base + decode(src)))
        point.children()
            .filter { it.tagName().substringAfter(':') == "navPoint" }
            .forEach { entry.children.add(convertNavPoint(it, base)) }
        return entry
    }

    /** Depth-first search by local (namespace-stripped) tag name. */
    private fun childrenNamed(root: Element, name: String): List<Element> {
        val out = ArrayList<Element>()
        fun walk(element: Element) {
            for (child in element.children()) {
                if (child.tagName().substringAfter(':').equals(name, ignoreCase = true)) out.add(child)
                walk(child)
            }
        }
        walk(root)
        return out
    }

    private fun guessTitle(file: File): String = try {
        val doc = Jsoup.parse(file.readText(), "UTF-8")
        doc.select("title").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
            ?: doc.select("h1, h2, h3").firstOrNull()?.text()?.takeIf { it.isNotBlank() }
            ?: file.nameWithoutExtension
    } catch (e: Exception) {
        file.nameWithoutExtension
    }

    private fun decode(s: String): String = s.replace("&amp;", "&").replace("%20", " ")

    /** Collapses ../ and ./ segments of a path inside the book. */
    private fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val out = ArrayList<String>()
        for (part in parts) {
            when (part) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(part)
            }
        }
        return out.joinToString("/")
    }
}
