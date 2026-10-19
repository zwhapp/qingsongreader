package com.qingsongreader.dict

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

enum class PackFormat { JSON, CSV }

data class DictionaryPack(
    val id: String,
    val label: String,
    val description: String,
    val format: PackFormat,
    val sizeLabel: String,
    val license: String,
    /** File name inside the upstream data repository, e.g. "ci.csv". */
    val path: String
) {
    /**
     * Candidate URLs, tried in order.
     *
     * raw.githubusercontent.com is unreachable on many Chinese networks, which
     * is why the jsDelivr mirror (with domestic nodes) comes first; the GitHub
     * raw endpoints are the fallbacks.
     */
    val urls: List<String> = listOf(
        "https://cdn.jsdelivr.net/gh/pwxcoo/chinese-xinhua@master/data/$path",
        "https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/$path",
        "https://github.com/pwxcoo/chinese-xinhua/raw/master/data/$path"
    )
}

/**
 * Optional downloadable packs.
 *
 * The app ships a built-in character table (derived from the Unicode® Unihan
 * database) and never bundles third-party word lists: those are fetched only
 * when the user taps download, from public repositories that publish their
 * data under the licence shown next to each entry.
 */
object DictionaryPacks {

    val presets: List<DictionaryPack> = listOf(
        DictionaryPack(
            id = "ci",
            label = "现代汉语词语",
            description = "约 30 万条词语释义（ci.csv）",
            format = PackFormat.CSV,
            sizeLabel = "18 MB",
            license = "MIT · pwxcoo/chinese-xinhua",
            path = "ci.csv"
        ),
        DictionaryPack(
            id = "idiom",
            label = "成语词典",
            description = "成语含拼音、释义、出处与例句（idiom.json）",
            format = PackFormat.JSON,
            sizeLabel = "10 MB",
            license = "MIT · pwxcoo/chinese-xinhua",
            path = "idiom.json"
        ),
        DictionaryPack(
            id = "xiehouyu",
            label = "歇后语",
            description = "歇后语小词库（xiehouyu.json）",
            format = PackFormat.JSON,
            sizeLabel = "1.2 MB",
            license = "MIT · pwxcoo/chinese-xinhua",
            path = "xiehouyu.json"
        )
    )
}

class DictionaryDownloader(private val http: OkHttpClient) {

    /**
     * Streams [pack] into [dest] and reports 0-100 progress.
     *
     * Sources are tried in order: the user-supplied [mirrorUrl] (if any) first,
     * then the built-in mirrors. A dead or slow host fails fast so the next
     * candidate gets a chance.
     */
    suspend fun download(
        pack: DictionaryPack,
        dest: File,
        urlOverride: String? = null,
        onProgress: (Int) -> Unit
    ): Result<File> {
        val candidates = buildList {
            urlOverride?.takeIf { it.isNotBlank() }?.let { add(it.trimEnd('/') + "/" + pack.path) }
            addAll(pack.urls)
        }
        val failures = ArrayList<String>()
        for (url in candidates) {
            val attempt = runCatching { downloadFrom(url, dest, onProgress) }
            if (attempt.isSuccess) return attempt
            failures += "${url.take(48)}…（${attempt.exceptionOrNull()?.message ?: "失败"}）"
            onProgress(0)
        }
        return Result.failure(
            IllegalStateException("所有下载源均不可用，请检查网络或填写镜像地址")
        )
    }

    private fun downloadFrom(url: String, dest: File, onProgress: (Int) -> Unit): File {
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("空响应")
            val total = body.contentLength()
            var downloaded = 0L
            body.byteStream().use { input ->
                FileOutputStream(dest).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0) {
                            onProgress(((downloaded.toFloat() / total.toFloat()) * 100).roundToInt())
                        }
                    }
                }
            }
            onProgress(100)
            if (dest.length() == 0L) error("下载内容为空")
            return dest
        }
    }
}
