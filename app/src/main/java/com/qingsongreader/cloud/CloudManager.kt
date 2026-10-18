package com.qingsongreader.cloud

import android.content.Context
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Holds per-provider credentials and tokens, and builds ready-to-use clients.
 *
 * Everything is the user's own: they register an app with the provider and
 * authorise this app from inside it. No shared secret ships with OpenReader.
 */
class CloudManager(context: Context) {

    private val prefs = context.getSharedPreferences("cloud", Context.MODE_PRIVATE)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .callTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun credentials(type: CloudType): Pair<String, String> =
        prefs.getString("${type.name}_id", "").orEmpty() to
            prefs.getString("${type.name}_secret", "").orEmpty()

    fun saveCredentials(type: CloudType, id: String, secret: String) {
        prefs.edit()
            .putString("${type.name}_id", id.trim())
            .putString("${type.name}_secret", secret.trim())
            .apply()
    }

    fun saveToken(type: CloudType, token: CloudToken) {
        prefs.edit()
            .putString("${type.name}_access", token.accessToken)
            .putString("${type.name}_refresh", token.refreshToken)
            .putLong("${type.name}_expires", token.expiresAt)
            .putString("${type.name}_drive", token.driveId)
            .apply()
    }

    fun token(type: CloudType): CloudToken? {
        val access = prefs.getString("${type.name}_access", "").orEmpty()
        if (access.isBlank()) return null
        return CloudToken(
            accessToken = access,
            refreshToken = prefs.getString("${type.name}_refresh", "").orEmpty(),
            expiresAt = prefs.getLong("${type.name}_expires", 0L),
            driveId = prefs.getString("${type.name}_drive", "").orEmpty()
        )
    }

    fun disconnect(type: CloudType) {
        prefs.edit()
            .remove("${type.name}_access")
            .remove("${type.name}_refresh")
            .remove("${type.name}_expires")
            .remove("${type.name}_drive")
            .apply()
    }

    fun isConnected(type: CloudType): Boolean = token(type) != null

    /** Builds a client with the stored token, or null when not configured. */
    fun client(type: CloudType): CloudClient? {
        val (id, secret) = credentials(type)
        if (id.isBlank() || secret.isBlank()) return null
        return when (type) {
            CloudType.BAIDU -> BaiduPanClient(id, secret, http).apply { token = this@CloudManager.token(type) }
            CloudType.ALIYUN -> AliyunDriveClient(id, secret, http).apply { token = this@CloudManager.token(type) }
            CloudType.WEBDAV -> null
        }
    }

    /** Client without a token, used only to build the authorization URL. */
    fun authClient(type: CloudType): CloudClient? {
        val (id, secret) = credentials(type)
        if (id.isBlank() || secret.isBlank()) return null
        return when (type) {
            CloudType.BAIDU -> BaiduPanClient(id, secret, http)
            CloudType.ALIYUN -> AliyunDriveClient(id, secret, http)
            CloudType.WEBDAV -> null
        }
    }
}
