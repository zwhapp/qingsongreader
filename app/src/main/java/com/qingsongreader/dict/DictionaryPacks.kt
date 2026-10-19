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
     * Reachability from Chinese mobile networks is volatile: raw.githubusercontent
     * is blocked on many ISPs and cdn.jsdelivr.net's domestic nodes come and go,
     * so we fan out across the alternative jsDelivr edges (Fastly / Gcore /
     * Cloudflare-testingcf), then gh proxy services, then the GitHub origins.
     * The user can always supply their own mirror prefix on top.
     */
    val urls: List<String> = listOf(
        "https://fastly.jsdelivr.net/gh/pwxcoo/chinese-xinhua@master/data/$path",
        "https://gcore.jsdelivr.net/gh/pwxcoo/chinese-xinhua@master/data/$path",
        "https://testingcf.jsdelivr.net/gh/pwxcoo/chinese-xinhua@master/data/$path",
        "https://cdn.jsdelivr.net/gh/pwxcoo/chinese-xinhua@master/data/$path",
        "https://ghproxy.net/https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/$path",
        "https://gh-proxy.com/https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data/$path",
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
            IllegalStateException("所有下载源均不可用（" + failures.joinToString("; ") + "）。请检查网络，或在镜像地址里填写可直接访问的数据目录 URL。")
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
