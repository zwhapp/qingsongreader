package com.qingsongreader.features.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.qingsongreader.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
 * the semantic version against the running [BuildConfig.VERSION_NAME],
 * downloads the APK asset and hands it to the system installer through a
 * FileProvider (required since Android 7 / scoped storage).
 *
 * The whole update state machine lives here (not in a ViewModel) so the APK
 * download runs on an application-lifetime scope: leaving the settings page
 * or the app entirely does not cancel it. The download also resumes via an
 * HTTP Range request from the cached partial file instead of restarting.
 *
 * This module only talks to api.github.com and the release asset CDN; it stores
 * no credentials.
 */
object Updater {

    private const val RELEASES_URL =
        "https://api.github.com/repos/zwhapp/qingsongreader/releases/latest"
    // Fallback channel when api.github.com is unreachable: the releases/latest
    // page 302-redirects to /releases/tag/vX.Y.Z, which carries the version.
    private const val LATEST_PAGE_URL =
        "https://github.com/zwhapp/qingsongreader/releases/latest"
    private const val ASSET_URL_FMT =
        "https://github.com/zwhapp/qingsongreader/releases/download/v%s/qingsongreader-v%s-debug.apk"
    const val REPO_URL = "https://github.com/zwhapp/qingsongreader"
    private const val FILE_PROVIDER_AUTHORITY = "com.qingsongreader.fileprovider"

    /**
     * Optional acceleration mirror prefix (URL-proxy style, e.g.
     * "https://gh-proxy.com/"). Empty = direct connection. Synced from
     * ReaderSettings by the settings ViewModel.
     */
    @Volatile
    var mirror: String = ""

    private fun applyMirror(url: String): String =
        if (mirror.isBlank()) url else mirror.trimEnd('/') + "/" + url

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
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

    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Available(val info: ReleaseInfo) : UpdateState
        data class UpToDate(val version: String) : UpdateState
        data class Downloading(val progress: Int) : UpdateState
        data class Ready(val file: File) : UpdateState
        data object Installed : UpdateState
        data class Error(val message: String) : UpdateState
    }

    // Application-lifetime scope: the download survives navigation and the
    // activity being destroyed; only process death stops it, and even then
    // the partial file is reused on the next attempt.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    fun check() {
        scope.launch {
            _state.value = UpdateState.Checking
            latestRelease()
                .onSuccess { info ->
                    _state.value =
                        if (isNewer(info.version, BuildConfig.VERSION_NAME) && info.apkUrl != null) {
                            UpdateState.Available(info)
                        } else {
                            UpdateState.UpToDate(info.version)
                        }
                }
                .onFailure {
                    val hint = if (mirror.isBlank())
                        "无法连接 GitHub，可在下方「加速镜像」中选择一个节点后重试"
                    else "检查失败，可换一个加速镜像后重试"
                    _state.value = UpdateState.Error("${it.message ?: "网络错误"}（$hint）")
                }
        }
    }

    fun downloadAndInstall(context: Context, info: ReleaseInfo) {
        val url = info.apkUrl?.let { applyMirror(it) } ?: return
        scope.launch {
            _state.value = UpdateState.Downloading(0)
            downloadApk(context, info.version, url) { progress ->
                _state.value = UpdateState.Downloading(progress)
            }.onSuccess { file ->
                _state.value = UpdateState.Ready(file)
                installApk(context, file)
                _state.value = UpdateState.Installed
            }.onFailure {
                _state.value = UpdateState.Error(it.message ?: "下载失败")
            }
        }
    }

    fun reset() {
        _state.value = UpdateState.Idle
    }

    /**
     * Latest release, or a failure if every channel fails.
     *
     * Tries two channels, each through the configured mirror when set:
     * 1. the GitHub REST API (full release notes + asset list);
     * 2. the releases/latest web page — its final redirect URL contains the
     *    tag, from which the APK asset URL is constructed by convention.
     */
    suspend fun latestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (url in listOf(applyMirror(RELEASES_URL), applyMirror(LATEST_PAGE_URL))) {
            try {
                return@withContext Result.success(fetchRelease(url))
            } catch (t: Throwable) {
                last = t
            }
        }
        Result.failure(last ?: java.io.IOException("网络错误"))
    }

    private fun fetchRelease(url: String): ReleaseInfo {
        val req = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("GitHub HTTP ${resp.code}")
            val text = resp.body!!.string()
            val info: ReleaseInfo = if (text.trimStart().startsWith("{")) {
                parseApiJson(text)
            } else {
                // HTML page: derive the version from the final redirect URL.
                val finalUrl = resp.request.url.toString()
                val ver = Regex("/releases/tag/v?(\\d+\\.\\d+\\.\\d+)").find(finalUrl)
                    ?.groupValues?.get(1)
                    ?: throw IllegalStateException("未能从页面解析版本号")
                ReleaseInfo(
                    version = ver,
                    name = "v$ver",
                    body = "",
                    htmlUrl = REPO_URL,
                    apkUrl = String.format(ASSET_URL_FMT, ver, ver),
                    publishedAt = ""
                )
            }
            return info
        }
    }

    private fun parseApiJson(json: String): ReleaseInfo {
        val obj = JSONObject(json)
        val tag = obj.optString("tag_name").removePrefix("v")
        val assets = obj.optJSONArray("assets")
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
        return ReleaseInfo(
            version = tag,
            name = obj.optString("name", tag),
            body = obj.optString("body", ""),
            htmlUrl = obj.optString("html_url", REPO_URL),
            apkUrl = apk ?: String.format(ASSET_URL_FMT, tag, tag),
            publishedAt = obj.optString("published_at", "")
        )
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

    /**
     * Downloads the release APK into cache/updates and reports 0-100.
     *
     * - Fully downloaded file for this version → returned instantly (cache hit).
     * - Partial file from an interrupted attempt → resumed with an HTTP Range
     *   request; a server that ignores Range (plain 200) restarts cleanly.
     * - On failure the partial file is kept for the next attempt.
     */
    suspend fun downloadApk(
        context: Context,
        version: String,
        url: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val dest = File(dir, "qingsongreader-$version.apk")
            if (dest.exists() && dest.length() > 0L) return@runCatching dest
            val part = File(dir, dest.name + ".part")
            val already = if (part.exists()) part.length() else 0L
            val req = Request.Builder().url(url)
                .apply { if (already > 0) header("Range", "bytes=$already-") }
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("下载失败 HTTP ${resp.code}")
                val ranged = resp.code == 206
                if (already > 0 && !ranged) part.delete() // server ignored Range
                val body = resp.body!!
                var soFar = if (ranged) already else 0L
                val total = if (ranged) soFar + body.contentLength() else body.contentLength()
                body.byteStream().use { input ->
                    FileOutputStream(part, ranged).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            soFar += read
                            if (total > 0) {
                                onProgress((soFar * 100 / total).toInt().coerceIn(0, 99))
                            }
                        }
                    }
                }
                onProgress(100)
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) error("更新包落盘失败")
                dest
            }
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
