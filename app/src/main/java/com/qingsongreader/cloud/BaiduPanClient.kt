package com.qingsongreader.cloud

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import org.json.JSONObject

/**
 * 百度网盘开放平台客户端。
 *
 * Uses the user's own app key/secret (applied at https://pan.baidu.com/union).
 * Scope: basic,netdisk. Downloads go through the PCS download endpoint with the
 * user's own access token, so nothing is proxied by us.
 */
class BaiduPanClient(
    private val appKey: String,
    private val appSecret: String,
    private val http: OkHttpClient
) : CloudClient {

    companion object {
        private const val REDIRECT = "oob"
        private const val AUTHORIZE = "https://openapi.baidu.com/oauth/2.0/authorize"
        private const val TOKEN = "https://openapi.baidu.com/oauth/2.0/token"
        private const val LIST = "https://pan.baidu.com/rest/2.0/xpan/file"
        private const val DOWNLOAD = "https://d.pcs.baidu.com/rest/2.0/pcs/file"
    }

    var token: CloudToken? = null

    override fun authorizeUrl(state: String): String =
        "$AUTHORIZE?response_type=code&client_id=$appKey&redirect_uri=$REDIRECT" +
            "&scope=basic,netdisk&display=mobile&state=$state"

    override suspend fun exchangeToken(code: String): Result<CloudToken> {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", appKey)
            .add("client_secret", appSecret)
            .add("redirect_uri", REDIRECT)
            .build()
        val request = Request.Builder().url(TOKEN).post(body).build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.has("error")) error(json.optString("error_description", json.optString("error")))
                val expires = System.currentTimeMillis() + json.optLong("expires_in", 2592000L) * 1000
                CloudToken(
                    accessToken = json.getString("access_token"),
                    refreshToken = json.optString("refresh_token"),
                    expiresAt = expires
                ).also { token = it }
            }
        }
    }

    override suspend fun list(folder: CloudFile): Result<List<CloudFile>> {
        val access = token?.accessToken ?: return Result.failure(IllegalStateException("未连接百度网盘"))
        val url = "$LIST?method=list&access_token=$access&dir=${java.net.URLEncoder.encode(folder.path, "UTF-8")}" +
            "&start=0&limit=500&order=name&folder=0"
        val request = Request.Builder().url(url).get().build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.has("errno") && json.optInt("errno") != 0) {
                    error("列表失败：${json.optString("errmsg", json.optString("errno"))}")
                }
                val array = json.optJSONArray("list") ?: return@use emptyList()
                val files = ArrayList<CloudFile>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    files.add(
                        CloudFile(
                            id = item.optString("fs_id"),
                            name = item.optString("server_filename"),
                            path = item.optString("path"),
                            isDir = item.optInt("isdir") == 1,
                            size = item.optLong("size")
                        )
                    )
                }
                files.sortedWith(compareByDescending<CloudFile> { it.isDir }.thenBy { it.name })
            }
        }
    }

    override suspend fun download(file: CloudFile, destDir: File): Result<File> {
        val access = token?.accessToken ?: return Result.failure(IllegalStateException("未连接百度网盘"))
        destDir.mkdirs()
        val target = File(destDir, file.name)
        val url = "$DOWNLOAD?method=download&access_token=$access&path=" +
            java.net.URLEncoder.encode(file.path, "UTF-8")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "pan.baidu.com")
            .get()
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("下载失败：HTTP ${response.code}")
                response.body?.byteStream()?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: error("空响应")
                if (target.length() == 0L) error("下载内容为空")
                target
            }
        }
    }
}
