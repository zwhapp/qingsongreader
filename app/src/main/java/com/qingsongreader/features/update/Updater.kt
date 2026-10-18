package com.qingsongreader.features.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * GitHub-Releases based updater.
 *
 * It queries the latest published release of the project repository, compares
 * the semantic version against the running [android.os.BuildConfig.VERSION_NAME],
 * downloads the APK asset and hands it to the system installer through a
 * FileProvider (required since Android 7 / scoped storage).
 *
 * This module only talks to api.github.com and the release asset CDN; it stores
 * no credentials.
 */
object Updater {

    private const val RELEASES_URL =
        "https://api.github.com/repos/zwhapp/qingsongreader/releases/latest"
    const val REPO_URL = "https://github.com/zwhapp/qingsongreader"
    private const val FILE_PROVIDER_AUTHORITY = "com.qingsongreader.fileprovider"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class ReleaseInfo(
        val version: String,
        val name: String,
        val body: String,
        val htmlUrl: String,
        val apkUrl: String?,
        val publishedAt: String
    )

    /** Latest release, or a failure if the network/API misbehaves. */
    suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url(RELEASES_URL)
                .header("Accept", "application/vnd.github+json")
                .build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw IllegalStateException("GitHub API HTTP ${resp.code}")
            val json = JSONObject(resp.body!!.string())
            val tag = json.optString("tag_name").removePrefix("v")
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            for (i in 0 until (assets?.length() ?: 0)) {
                val a = assets!!.getJSONObject(i)
                val nm = a.optString("name", "")
                val ct = a.optString("content_type", "")
                if (nm.endsWith(".apk", ignoreCase = true) ||
                    ct == "application/vnd.android.package-archive"
                ) {
                    apk = a.optString("browser_download_url")
                }
            }
            ReleaseInfo(
                version = tag,
                name = json.optString("name", tag),
                body = json.optString("body", ""),
                htmlUrl = json.optString("html_url", REPO_URL),
                apkUrl = apk,
                publishedAt = json.optString("published_at", "")
            )
        }
    }

    /** True when [remote] is a newer semantic version than [current]. */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote)
        val c = parseVersion(current)
        for (i in 0..2) {
            if (r[i] > c[i]) return true
            if (r[i] < c[i]) return false
        }
        return false
    }

    private fun parseVersion(v: String): IntArray {
        val parts = v.removePrefix("v").split(".")
            .mapNotNull { it.filter { ch -> ch.isDigit() }.toIntOrNull() }
        return IntArray(3) { i -> parts.getOrElse(i) { 0 } }
    }

    /** Downloads the APK into [Context.getCacheDir]/updates and reports 0-100. */
    suspend fun downloadApk(
        context: Context,
        url: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val dest = File(dir, "qingsongreader-update.apk")
            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) throw IllegalStateException("下载失败 HTTP ${resp.code}")
            val body = resp.body!!
            val total = body.contentLength()
            val input = body.byteStream()
            FileOutputStream(dest).use { out ->
                val buf = ByteArray(8192)
                var read: Int
                var soFar = 0L
                while (input.read(buf).also { read = it } != -1) {
                    out.write(buf, 0, read)
                    soFar += read
                    if (total > 0) onProgress((soFar * 100 / total).toInt().coerceIn(0, 100))
                }
            }
            onProgress(100)
            dest
        }
    }

    /** Launches the system package installer for a downloaded APK. */
    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, FILE_PROVIDER_AUTHORITY, file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
