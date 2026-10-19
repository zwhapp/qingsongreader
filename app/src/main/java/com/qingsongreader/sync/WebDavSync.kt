package com.qingsongreader.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Minimal WebDAV client used for "bring your own storage" sync: the user points
 * the app at a server they control (Nextcloud, ownCloud, nginx-webdav, ...).
 * There is no first-party cloud service in this app and therefore no storage
 * quota to buy.
 *
 * Syncs both the library JSON (books + highlights + bookmarks) and the book
 * files themselves, so a re-installed device gets its whole shelf back.
 */
class WebDavSync(
    baseUrl: String,
    user: String,
    password: String
) {

    private val baseUrl: String = baseUrl.trim().trimEnd('/')
    private val user: String = user
    private val password: String = password

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS) // book files can be tens of MB
        .build()

    private fun auth(): String = Credentials.basic(user, password)

    private fun ensureDirectory() {
        val request = Request.Builder()
            .url(baseUrl + "/")
            .header("Authorization", auth())
            .method("MKCOL", null)
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    private fun remoteUrl(name: String): String = baseUrl + "/" + name.trimStart('/')

    suspend fun upload(name: String, content: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            ensureDirectory()
            val request = Request.Builder()
                .url(remoteUrl(name))
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

    /** Uploads a binary file (a book) with 0-100 progress reporting. */
    suspend fun uploadFile(
        name: String,
        file: File,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            if (!file.exists()) error("本地文件不存在：${file.name}")
            ensureDirectory()
            val total = file.length().coerceAtLeast(1L)
            val body = object : okhttp3.RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = file.length()
                override fun writeTo(sink: okio.BufferedSink) {
                    file.inputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var sent = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            sink.write(buffer, 0, read)
                            sent += read
                            onProgress(((sent.toFloat() / total.toFloat()) * 100).toInt())
                        }
                    }
                }
            }
            val request = Request.Builder()
                .url(remoteUrl(name))
                .header("Authorization", auth())
                .put(body)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 201 && response.code != 204) {
                    error("上传失败：HTTP ${response.code}")
                }
            }
            onProgress(100)
        }
    }

    suspend fun download(name: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(remoteUrl(name))
                .header("Authorization", auth())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                response.body?.string().orEmpty()
            }
        }
    }

    /** Downloads a binary file (a book) into [dest] with 0-100 progress. */
    suspend fun downloadFile(
        name: String,
        dest: File,
        onProgress: (Int) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            dest.parentFile?.mkdirs()
            val request = Request.Builder()
                .url(remoteUrl(name))
                .header("Authorization", auth())
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                val body = response.body ?: error("空响应")
                val total = body.contentLength()
                var received = 0L
                body.byteStream().use { input ->
                    FileOutputStream(dest).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            received += read
                            if (total > 0) {
                                onProgress(((received.toFloat() / total.toFloat()) * 100).toInt())
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
