package com.qingsongreader.sync

import com.qingsongreader.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Baidu Netdisk (百度网盘) OpenAPI client.
 *
 * OAuth2 oob flow: the app opens the authorize URL, the user signs in with
 * their Baidu account and taps agree; the consent page then shows a one-time
 * code which the app extracts automatically (or the user pastes). The code is
 * exchanged for an access_token (30 days) plus a long-lived refresh_token.
 *
 * AppKey/SecretKey are developer credentials baked into the app at build time
 * (see app/build.gradle.kts) — end users never fill anything in.
 *
 * Per platform rules (apps created after 2026-06-03), listing / download can
 * only reach /apps/<AppName> — the directory the UI discloses in its footnote.
 */
object BaiduPanClient {

    const val APP_KEY: String = BuildConfig.BAIDU_APP_KEY
    const val APP_SECRET: String = BuildConfig.BAIDU_APP_SECRET
    const val APP_NAME: String = BuildConfig.BAIDU_APP_NAME
    /** The only directory the app may legally read, e.g. /apps/个人阅读. */
    const val APP_DIR: String = "/apps/$APP_NAME"

    private const val AUTHORIZE = "https://openapi.baidu.com/oauth/2.0/authorize"
    private const val TOKEN = "https://openapi.baidu.com/oauth/2.0/token"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class TokenPair(
        val accessToken: String,
        val refreshToken: String,
        val expiresAtMs: Long
    )

    data class CloudFile(val path: String, val name: String, val size: Long)

    fun configured(): Boolean = APP_KEY.isNotBlank() && APP_SECRET.isNotBlank()

    /**
     * Builds the authorize URL. `display=page` renders the full-page login,
     * which includes a QR-code tab (scan with the Baidu app to sign in);
     * `display=mobile` renders the touch-optimized account/password form.
     */
    fun authorizeUrl(display: String = "page"): String =
        "$AUTHORIZE?response_type=code&client_id=$APP_KEY&redirect_uri=oob" +
            "&scope=basic,netdisk&display=$display"

    /**
     * QR-code sign-in entry. Baidu's OAuth authorize page ships with QR login
     * disabled server-side (`qrcode: ''`), but the passport login page itself
     * has it enabled for desktop user agents. Signing in there and returning
     * to [authorizeUrl] via the `u` redirect lands on the consent page with a
     * live session, so the user only has to tap 授权.
     */
    fun qrLoginUrl(): String {
        val back = authorizeUrl("page")
        return "https://passport.baidu.com/v2/?login&u=" + URLEncoder.encode(back, "UTF-8")
    }

    /** Extracts an oob authorization code from the consent page text or URL. */
    fun extractCode(pageText: String): String? {
        val patterns = listOf(
            Regex("授权码[^0-9a-zA-Z]{0,20}([0-9a-zA-Z]{12,})"),
            Regex("\\bcode\\s*[=：:]\\s*\"?([0-9a-zA-Z]{12,})", RegexOption.IGNORE_CASE),
            Regex("\\b([0-9a-f]{24,64})\\b")
        )
        for (p in patterns) {
            p.find(pageText)?.groupValues?.get(1)?.let { return it }
        }
        return null
    }

    suspend fun exchangeCode(code: String): Result<TokenPair> = tokenCall(
        "grant_type=authorization_code&code=$code&client_id=$APP_KEY" +
            "&client_secret=$APP_SECRET&redirect_uri=oob"
    )

    suspend fun refresh(refreshToken: String): Result<TokenPair> = tokenCall(
        "grant_type=refresh_token&refresh_token=$refreshToken" +
            "&client_id=$APP_KEY&client_secret=$APP_SECRET"
    )

    private suspend fun tokenCall(query: String): Result<TokenPair> =
        withContext(Dispatchers.IO) {
            runCatching {
                val req = Request.Builder()
                    .url("$TOKEN?$query")
                    .header("User-Agent", "pan.baidu.com;qingsongreader")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val json = JSONObject(text)
                    if (json.has("error")) {
                        throw IllegalStateException(
                            json.optString("error_description", json.optString("error"))
                        )
                    }
                    TokenPair(
                        accessToken = json.getString("access_token"),
                        refreshToken = json.getString("refresh_token"),
                        expiresAtMs = System.currentTimeMillis() +
                            json.optLong("expires_in", 2592000L) * 1000L - 60_000L
                    )
                }
            }
        }

    /** Lists book files directly under [APP_DIR], filtered by extension. */
    suspend fun listBooks(accessToken: String): Result<List<CloudFile>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://pan.baidu.com/rest/2.0/xpan/file?method=list" +
                    "&access_token=$accessToken&dir=" + URLEncoder.encode(APP_DIR, "UTF-8") +
                    "&limit=1000&order=name"
                val req = Request.Builder().url(url)
                    .header("User-Agent", "pan.baidu.com;qingsongreader")
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val json = JSONObject(text)
                    if (json.optInt("errno", -1) != 0) {
                        throw IllegalStateException("网盘接口 errno=${json.optInt("errno")}")
                    }
                    val list = json.optJSONArray("list") ?: return@use emptyList<CloudFile>()
                    val exts = setOf("epub", "pdf", "mobi", "azw3", "txt", "md")
                    buildList {
                        for (i in 0 until list.length()) {
                            val f = list.getJSONObject(i)
                            if (f.optInt("isdir", 0) == 1) continue
                            val path = f.optString("path")
                            val name = f.optString("server_filename", path.substringAfterLast('/'))
                            if (name.substringAfterLast('.', "").lowercase() in exts) {
                                add(CloudFile(path, name, f.optLong("size", 0L)))
                            }
                        }
                    }
                }
            }
        }

    /** Streams a cloud file to [dest] with progress 0..100. */
    suspend fun download(
        accessToken: String,
        file: CloudFile,
        dest: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://pan.baidu.com/rest/2.0/xpan/file?method=download" +
                "&access_token=$accessToken&path=" + URLEncoder.encode(file.path, "UTF-8")
            val req = Request.Builder().url(url)
                // The dlink endpoint rejects unknown user agents.
                .header("User-Agent", "netdisk;qingsongreader")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("下载失败 HTTP ${resp.code}")
                val body = resp.body ?: throw IllegalStateException("空响应")
                val total = body.contentLength()
                var soFar = 0L
                dest.parentFile?.mkdirs()
                body.byteStream().use { input ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            soFar += read
                            if (total > 0) onProgress((soFar * 100 / total).toInt().coerceIn(0, 99))
                        }
                    }
                }
                onProgress(100)
                dest
            }
        }
    }
}
