package com.qingsongreader.reader

import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.TextAlignSetting
import com.qingsongreader.data.prefs.ThemeMode
import com.qingsongreader.data.prefs.ViewMode

object ThemePalette {
    fun background(mode: ThemeMode): String = when (mode) {
        ThemeMode.LIGHT -> "#FFFFFF"
        ThemeMode.DARK -> "#121212"
        ThemeMode.SEPIA -> "#F4ECD8"
    }

    fun foreground(mode: ThemeMode): String = when (mode) {
        ThemeMode.LIGHT -> "#1A1A1A"
        ThemeMode.DARK -> "#E6E6E6"
        ThemeMode.SEPIA -> "#4B3B2A"
    }

    fun link(mode: ThemeMode): String = when (mode) {
        ThemeMode.LIGHT -> "#1A73E8"
        ThemeMode.DARK -> "#8AB4F8"
        ThemeMode.SEPIA -> "#8A5A2B"
    }
}

object HtmlFactory {

    private fun fontStack(family: String): String = when (family) {
        "serif" -> "Georgia, 'Noto Serif', 'Source Han Serif SC', serif"
        "sans" -> "'Noto Sans', 'Source Han Sans SC', system-ui, sans-serif"
        "mono" -> "'Fira Code', monospace"
        else -> "system-ui, 'Noto Sans', 'Source Han Sans SC', sans-serif"
    }

    fun chapterHtml(bodyHtml: String, chapterTitle: String, settings: ReaderSettings): String {
        // E-ink / Kindle mode: drop all colour, render pure black on white so the
        // electrophoretic panel does not ghost and refreshes less.
        val bg = if (settings.einkMode) "#FFFFFF" else ThemePalette.background(settings.themeMode)
        val fg = if (settings.einkMode) "#000000" else ThemePalette.foreground(settings.themeMode)
        val link = if (settings.einkMode) "#000000" else ThemePalette.link(settings.themeMode)
        val fontFamilyCss = if (settings.useSerif || settings.einkMode) {
            "Georgia, 'Noto Serif', 'Source Han Serif SC', serif"
        } else {
            fontStack(settings.fontFamily)
        }
        val align = if (settings.textAlign == TextAlignSetting.JUSTIFY) "justify" else "left"
        val margin = settings.pageMarginDp
        val paged = settings.viewMode == ViewMode.PAGED
        val bodyClass = (if (paged) "paged" else "scrolled") + if (settings.einkMode) " eink" else ""
        val colWidth = "calc(100vw - ${margin * 2}px)"
        val gap = "${margin * 2}px"

        return """
<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=5.0, user-scalable=yes" />
<style>
  :root {
    --col-width: $colWidth;
    --gap: $gap;
    --margin: ${margin}px;
  }
  html { height: 100%; overflow: hidden; -webkit-text-size-adjust: 100%; scroll-behavior: auto !important; }
  body {
    margin: 0;
    padding: 0 var(--margin);
    height: 100%;
    box-sizing: border-box;
    background: $bg;
    color: $fg;
    font-family: $fontFamilyCss;
    font-size: ${settings.fontSizeSp}px;
    line-height: ${settings.lineHeight};
    text-align: $align;
    word-wrap: break-word;
  }
  body.paged {
    /* The body itself is the paging scroll container: the multi-column layout
       overflows it horizontally and paging scrolls it. (Scrolling the WebView
       viewport does not work: it expands to the content width.) */
    overflow-x: auto;
    overflow-y: hidden;
    column-width: var(--col-width);
    column-gap: var(--gap);
    column-fill: auto;
    -webkit-column-fill: auto;
  }
  body.scrolled { overflow-y: auto; overflow-x: hidden; }
  p {
    margin: 0 0 ${settings.paragraphSpacing}em 0;
    /* Books often ship inline line-heights; force ours so the slider has a
       visible effect on every title. */
    line-height: ${settings.lineHeight} !important;
  }
  li, div, span { line-height: inherit; }
  h1, h2, h3, h4 { line-height: 1.3; page-break-after: avoid; break-after: avoid; }
  img, svg { max-width: 100%; height: auto; }
  pre { white-space: pre-wrap; word-break: break-word; background: rgba(128,128,128,0.12); padding: 8px; border-radius: 6px; }
  code { font-family: 'Fira Code', monospace; font-size: 0.9em; }
  a { color: $link; ${if (settings.einkMode) "text-decoration: underline;" else ""} }
  a[href] { cursor: pointer; }
  table { max-width: 100%; }
  .or-title { font-size: 1.3em; font-weight: 700; margin: 1.2em 0; }
  .or-hl { border-radius: 3px; }
  .or-search { background: #FFD54F; color: #000; }
  /* E-ink (Bigme B751C & friends): strip every transient/decorative effect,
     lift stroke contrast, and make illustrations dither-friendly. */
  body.eink { font-weight: 500; overscroll-behavior: none; }
  body.eink * { box-shadow: none !important; text-shadow: none !important; }
  body.eink img, body.eink svg { filter: grayscale(100%) contrast(1.12); }
  body.eink pre { background: #F2F2F2; border: 1px solid #DDDDDD; border-radius: 0; }
  body.eink .or-search { background: #000000; color: #FFFFFF; }
</style>
</head>
<body class="$bodyClass">
  <div class="or-title">${escapeHtml(chapterTitle)}</div>
  <div id="or-content">$bodyHtml</div>
</body>
</html>
        """.trimIndent()
    }

    /** Wraps a plain-text book into a single scrollable HTML document. */
    fun plainTextHtml(text: String, title: String, settings: ReaderSettings): String {
        val paragraphs = text.split(Regex("\\n{1,}"))
            .filter { it.isNotBlank() }
            .joinToString("\n") { "<p>${escapeHtml(it.trim())}</p>" }
        return chapterHtml(paragraphs, title, settings)
    }

    /** Very small Markdown subset: headings, bold/italic, lists, code, quotes. */
    fun markdownHtml(md: String, title: String, settings: ReaderSettings): String {
        val out = StringBuilder()
        var inList = false
        var inCode = false
        md.lineSequence().forEach { raw ->
            val line = raw.trimEnd()
            if (line.startsWith("```")) {
                if (inCode) {
                    out.append("</code></pre>")
                    inCode = false
                } else {
                    out.append("<pre><code>")
                    inCode = true
                }
                return@forEach
            }
            if (inCode) {
                out.append(escapeHtml(line)).append('\n')
                return@forEach
            }
            when {
                line.startsWith("# ") -> out.append("<h1>").append(inline(line.removePrefix("# "))).append("</h1>")
                line.startsWith("## ") -> out.append("<h2>").append(inline(line.removePrefix("## "))).append("</h2>")
                line.startsWith("### ") -> out.append("<h3>").append(inline(line.removePrefix("### "))).append("</h3>")
                line.startsWith("> ") -> out.append("<blockquote>").append(inline(line.removePrefix("> "))).append("</blockquote>")
                line.startsWith("- ") || line.startsWith("* ") -> {
                    if (!inList) {
                        out.append("<ul>")
                        inList = true
                    }
                    out.append("<li>").append(inline(line.removePrefix("- ").removePrefix("* "))).append("</li>")
                }
                line.isBlank() -> {
                    if (inList) {
                        out.append("</ul>")
                        inList = false
                    }
                }
                else -> out.append("<p>").append(inline(line)).append("</p>")
            }
        }
        if (inList) out.append("</ul>")
        if (inCode) out.append("</code></pre>")
        return chapterHtml(out.toString(), title, settings)
    }

    private fun inline(s: String): String {
        var t = escapeHtml(s)
        t = t.replace(Regex("\\*\\*(.+?)\\*\\*"), "<strong>\$1</strong>")
        t = t.replace(Regex("(?<!\\*)\\*(?!\\*)(.+?)(?<!\\*)\\*(?!\\*)"), "<em>\$1</em>")
        t = t.replace(Regex("`([^`]+)`"), "<code>\$1</code>")
        t = t.replace(Regex("\\[(.+?)\\]\\((.+?)\\)"), "<a href=\"\$2\">\$1</a>")
        return t
    }

    fun escapeHtml(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
