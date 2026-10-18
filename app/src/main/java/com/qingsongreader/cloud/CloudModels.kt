package com.qingsongreader.cloud

import java.io.File

enum class CloudType(val label: String) {
    WEBDAV("WebDAV"),
    BAIDU("百度网盘"),
    ALIYUN("阿里云盘")
}

data class CloudFile(
    val id: String,
    val name: String,
    val path: String = "",
    val isDir: Boolean = false,
    val size: Long = 0L
) {
    val extension: String get() = name.substringAfterLast('.', "").lowercase()

    fun isImportable(): Boolean = !isDir && extension in SUPPORTED_EXTENSIONS

    companion object {
        private val SUPPORTED_EXTENSIONS = setOf("epub", "pdf", "mobi", "azw3", "azw", "txt", "md", "markdown")

        fun root(type: CloudType) = CloudFile(
            id = if (type == CloudType.ALIYUN) "root" else "/",
            name = "根目录",
            path = if (type == CloudType.ALIYUN) "" else "/",
            isDir = true
        )
    }
}

data class CloudToken(
    val accessToken: String,
    val refreshToken: String = "",
    val expiresAt: Long = 0L,
    /** Aliyun needs the drive id for every file call. */
    val driveId: String = ""
) {
    fun expired(): Boolean = expiresAt > 0 && System.currentTimeMillis() > expiresAt - 60_000
}

interface CloudClient {
    /** OAuth page the user has to sign in to. */
    fun authorizeUrl(state: String = "openreader"): String

    /** Exchanges the authorization code for tokens. */
    suspend fun exchangeToken(code: String): Result<CloudToken>

    suspend fun list(folder: CloudFile): Result<List<CloudFile>>

    /** Downloads into [destDir] and returns the local file. */
    suspend fun download(file: CloudFile, destDir: File): Result<File>
}
