package com.qingsongreader.ui.reader

import android.webkit.JavascriptInterface
import android.webkit.WebView

class ReaderJsBridge(
    onLink: (String) -> Unit,
    onSelection: (String) -> Unit
) {
    private val onLinkCb = onLink
    private val onSelectionCb = onSelection

    @JavascriptInterface
    fun onLink(href: String) = onLinkCb(href)

    @JavascriptInterface
    fun onSelection(text: String) = onSelectionCb(text)
}

data class SelectionRange(val start: Int, val end: Int, val text: String)

/**
 * Thin wrapper around the reader WebView: every call is a JS evaluation,
 * results come back asynchronously through callbacks.
 */
class ReaderWebController {

    private var webView: WebView? = null

    fun attach(webView: WebView) {
        this.webView = webView
    }

    fun detach() {
        webView = null
    }

    fun loadChapter(html: String, baseUrl: String) {
        webView?.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
    }

    fun runJs(js: String, callback: ((String) -> Unit)? = null) {
        webView?.evaluateJavascript(js, callback)
    }

    fun pageCount(cb: (Int) -> Unit) {
        runJs("OR.pageCount()") { cb(unwrap(it).toIntOrNull() ?: 1) }
    }

    fun currentPage(cb: (Int) -> Unit) {
        runJs("OR.currentPage()") { cb(unwrap(it).toIntOrNull() ?: 0) }
    }

    fun progress(cb: (Float) -> Unit) {
        runJs("OR.progress()") { cb(unwrap(it).toFloatOrNull() ?: 0f) }
    }

    fun goToPage(page: Int) = runJs("OR.goToPage($page)")

    fun goToProgress(value: Float) = runJs("OR.goToProgress(${value.coerceIn(0f, 1f)})")

    fun setPaged(enabled: Boolean) = runJs("OR.setPaged($enabled)")

    fun highlight(start: Int, end: Int, color: String, id: String) =
        runJs("""OR.highlight($start,$end,"$color","$id")""")

    fun removeHighlight(id: String) = runJs("""OR.removeHighlight("$id")""")

    fun clearSelection() = runJs("OR.clearSelection()")

    fun textLength(cb: (Int) -> Unit) {
        runJs("OR.textLength()") { cb(unwrap(it).toIntOrNull() ?: 0) }
    }

    fun chapterText(cb: (String) -> Unit) {
        runJs("JSON.stringify(OR.chapterText())") { cb(unwrap(it)) }
    }

    fun pageText(cb: (String) -> Unit) {
        runJs("JSON.stringify(OR.pageText())") { cb(unwrap(it)) }
    }

    fun selectedRange(cb: (SelectionRange?) -> Unit) {
        runJs("JSON.stringify(OR.selectedRange())") { raw ->
            val value = unwrap(raw)
            if (value.isBlank() || value == "null") {
                cb(null)
            } else {
                val range = runCatching {
                    val json = org.json.JSONObject(value)
                    SelectionRange(
                        start = json.optInt("start"),
                        end = json.optInt("end"),
                        text = json.optString("text")
                    )
                }.getOrNull()
                cb(range)
            }
        }
    }

    fun markSearch(keyword: String, cb: (Int) -> Unit) {
        val safe = keyword.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        runJs("""OR.markSearch("$safe")""") { cb(unwrap(it).toIntOrNull() ?: 0) }
    }

    fun clearSearch() = runJs("OR.clearSearch()")

    fun findNext(keyword: String, cb: (Boolean) -> Unit) {
        val safe = keyword.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        runJs("""OR.findNext("$safe")""") { cb(unwrap(it).toBooleanStrictOrNull() ?: false) }
    }

    fun findPrev(keyword: String, cb: (Boolean) -> Unit) {
        val safe = keyword.replace("\\", "\\\\").replace("'", "\\'").replace("\"", "\\\"")
        runJs("""OR.findPrev("$safe")""") { cb(unwrap(it).toBooleanStrictOrNull() ?: false) }
    }

    /** evaluateJavascript hands back a JSON-encoded value; turn it into plain text. */
    private fun unwrap(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        return runCatching {
            val value = org.json.JSONTokener(trimmed).nextValue()
            when {
                value === org.json.JSONObject.NULL -> ""
                value is String -> value
                else -> value.toString()
            }
        }.getOrDefault(trimmed.trim('"'))
    }
}
