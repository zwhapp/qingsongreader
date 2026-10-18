package com.qingsongreader.sync

import com.qingsongreader.data.prefs.ReaderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Minimal WebDAV client used for "bring your own storage" sync: the user points
 * the app at a server they control (Nextcloud, ownCloud, nginx-webdav, ...).
 * There is no first-party cloud service in this app and therefore no storage
 * quota to buy.
 */
class WebDavSync(private val settings: ReaderSettings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    private fun base(): String = settings.webdavUrl.trim().trimEnd('/')

    private fun auth(): String = Credentials.basic(settings.webdavUser, settings.webdavPassword)

    private fun ensureDirectory() {
        val request = Request.Builder()
            .url(base() + "/")
            .header("Authorization", auth())
            .method("MKCOL", null)
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    suspend fun upload(name: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureDirectory()
            val request = Request.Builder()
                .url(base() + "/" + name)
                .header("Authorization", auth())
                .put(content.toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                    error("上传失败：HTTP ${response.code}")
                }
            }
        }
    }

    suspend fun download(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(base() + "/" + name)
                .header("Authorization", auth())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
    }
}
