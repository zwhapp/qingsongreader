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
    val url: String,
    val format: PackFormat,
    val sizeLabel: String,
    val license: String
)

/**
 * Optional downloadable packs.
 *
 * The app ships a built-in character table (derived from the Unicode® Unihan
 * database) and never bundles third-party word lists: those are fetched only
 * when the user taps download, from public repositories that publish their
 * data under the licence shown next to each entry.
 */
object DictionaryPacks {

    private const val BASE = "https://raw.githubusercontent.com/pwxcoo/chinese-xinhua/master/data"

    val presets: List<DictionaryPack> = listOf(
        DictionaryPack(
            id = "ci",
            label = "现代汉语词语",
            description = "约 30 万条词语释义（ci.csv）",
            url = "$BASE/ci.csv",
            format = PackFormat.CSV,
            sizeLabel = "18 MB",
            license = "MIT · pwxcoo/chinese-xinhua"
        ),
        DictionaryPack(
            id = "idiom",
            label = "成语词典",
            description = "成语含拼音、释义、出处与例句（idiom.json）",
            url = "$BASE/idiom.json",
            format = PackFormat.JSON,
            sizeLabel = "10 MB",
            license = "MIT · pwxcoo/chinese-xinhua"
        ),
        DictionaryPack(
            id = "xiehouyu",
            label = "歇后语",
            description = "歇后语小词库（xiehouyu.json）",
            url = "$BASE/xiehouyu.json",
            format = PackFormat.JSON,
            sizeLabel = "1.2 MB",
            license = "MIT · pwxcoo/chinese-xinhua"
        )
    )
}

class DictionaryDownloader(private val http: OkHttpClient) {

    /**
     * Streams [pack] into [dest] and reports 0-100 progress.
     * A mirror URL can be substituted by the caller through [urlOverride]
     * when the default host is slow or unreachable.
     */
    suspend fun download(
        pack: DictionaryPack,
        dest: File,
        urlOverride: String? = null,
        onProgress: (Int) -> Unit
    ): Result<File> {
        return runCatching {
            dest.parentFile?.mkdirs()
            val request = Request.Builder().url(urlOverride?.takeIf { it.isNotBlank() } ?: pack.url).get().build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
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
                dest
            }
        }
    }
}
