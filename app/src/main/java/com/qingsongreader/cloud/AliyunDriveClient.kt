package com.qingsongreader.cloud

import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * 阿里云盘开放平台客户端。
 *
 * Uses the user's own app id/secret (applied at https://open.alipan.com).
 * File listing/download go through the official OpenAPI with the user's token.
 */
class AliyunDriveClient(
    private val appId: String,
    private val appSecret: String,
    private val http: OkHttpClient
) : CloudClient {

    companion object {
        private const val BASE = "https://openapi.alipan.com"
        private const val REDIRECT = "https://openreader.app/oauth/aliyun"
        private const val SCOPE = "user:base,file:all:read"
    }

    var token: CloudToken? = null

    override fun authorizeUrl(state: String): String =
        "$BASE/oauth/authorize?client_id=$appId&response_type=code&scope=$SCOPE" +
            "&redirect_uri=$REDIRECT&state=$state"

    override suspend fun exchangeToken(code: String): Result<CloudToken> {
        val payload = JSONObject()
            .put("client_id", appId)
            .put("client_secret", appSecret)
            .put("grant_type", "authorization_code")
            .put("code", code)
        val request = Request.Builder()
            .url("$BASE/oauth/access_token")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (json.has("code") && json.optString("code").isNotBlank()) {
                    error(json.optString("message", json.optString("code")))
                }
                val access = json.getString("access_token")
                val expires = System.currentTimeMillis() + json.optLong("expires_in", 7200L) * 1000
                val driveId = fetchDriveId(access).getOrDefault("")
                CloudToken(
                    accessToken = access,
                    refreshToken = json.optString("refresh_token"),
                    expiresAt = expires,
                    driveId = driveId
                ).also { token = it }
            }
        }
    }

    private fun fetchDriveId(access: String): Result<String> {
        val request = Request.Builder()
            .url("$BASE/adrive/v1.0/user/getDriveInfo")
            .header("Authorization", "Bearer $access")
            .get()
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("获取 drive_id 失败：HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty()).optString("default_drive_id")
            }
        }
    }

    override suspend fun list(folder: CloudFile): Result<List<CloudFile>> {
        val access = token?.accessToken ?: return Result.failure(IllegalStateException("未连接阿里云盘"))
        val driveId = token?.driveId ?: return Result.failure(IllegalStateException("缺少 drive_id"))
        val payload = JSONObject()
            .put("drive_id", driveId)
            .put("parent_file_id", folder.id)
            .put("limit", 200)
            .put("order_by", "name")
            .put("order_direction", "ASC")
        val request = Request.Builder()
            .url("$BASE/adrive/v1.0/openFile/list")
            .header("Authorization", "Bearer $access")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("列表失败：HTTP ${response.code}")
                val json = JSONObject(response.body?.string().orEmpty())
                val array = json.optJSONArray("items") ?: return@use emptyList()
                val files = ArrayList<CloudFile>()
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    files.add(
                        CloudFile(
                            id = item.optString("file_id"),
                            name = item.optString("name"),
                            path = item.optString("file_id"),
                            isDir = item.optString("type") == "folder",
                            size = item.optLong("size")
                        )
                    )
                }
                files.sortedWith(compareByDescending<CloudFile> { it.isDir }.thenBy { it.name })
            }
        }
    }

    override suspend fun download(file: CloudFile, destDir: File): Result<File> {
        val access = token?.accessToken ?: return Result.failure(IllegalStateException("未连接阿里云盘"))
        val driveId = token?.driveId ?: return Result.failure(IllegalStateException("缺少 drive_id"))
        val payload = JSONObject()
            .put("drive_id", driveId)
            .put("file_id", file.id)
        val urlRequest = Request.Builder()
            .url("$BASE/adrive/v1.0/openFile/getDownloadUrl")
            .header("Authorization", "Bearer $access")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val downloadUrl = runCatching {
            http.newCall(urlRequest).execute().use { response ->
                if (!response.isSuccessful) error("获取下载链接失败：HTTP ${response.code}")
                JSONObject(response.body?.string().orEmpty()).optString("url").takeIf { it.isNotBlank() }
                    ?: error("没有返回下载链接")
            }
        }.getOrElse { return Result.failure(it) }

        destDir.mkdirs()
        val target = File(destDir, file.name)
        val request = Request.Builder()
            .url(downloadUrl)
            .header("User-Agent", "OpenReader")
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
