package com.qingsongreader.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingsongreader.BuildConfig
import com.qingsongreader.OpenReaderApp
import com.qingsongreader.cloud.CloudManager
import com.qingsongreader.features.update.Updater
import com.qingsongreader.features.update.Updater.ReleaseInfo
import com.qingsongreader.cloud.CloudType
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.dict.DictionaryDownloader
import com.qingsongreader.dict.DictionaryPack
import com.qingsongreader.dict.DictionaryRepository
import com.qingsongreader.dict.PackFormat
import com.qingsongreader.sync.SyncManager
import com.qingsongreader.sync.WebDavSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository(app)
    private val syncManager = SyncManager(app)
    private val dictionary = DictionaryRepository(app)

    val settings: StateFlow<ReaderSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    /** UI state for the update checker. */
    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Available(val info: ReleaseInfo) : UpdateState
        data class UpToDate(val version: String) : UpdateState
        data class Downloading(val progress: Int) : UpdateState
        data class Ready(val file: java.io.File) : UpdateState
        data object Installed : UpdateState
        data class Error(val message: String) : UpdateState
    }

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Number of offline / cached dictionary entries. */
    val dictCount: StateFlow<Int> = dictionary.count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun update(mutate: (ReaderSettings) -> ReaderSettings) {
        viewModelScope.launch { repository.update(mutate) }
    }

    suspend fun importDictionary(uri: Uri): Result<Int> = dictionary.import(uri)

    suspend fun clearDictionary() {
        dictionary.clearCache()
        dictionary.clearLocal()
    }

    /** True once both shipped dictionaries have been imported. */
    fun builtInImported(): Boolean {
        val prefs = app.getSharedPreferences(OpenReaderApp.DICT_PREFS, android.content.Context.MODE_PRIVATE)
        return prefs.getBoolean("hanzi_imported", false) && prefs.getBoolean("cedict_imported", false)
    }

    /** Re-runs the built-in import (used after clearing the dictionary). */
    suspend fun reimportBuiltIn(): Result<Int> {
        val hanzi = dictionary.importBuiltInHanzi()
        val cedict = dictionary.importBuiltInCedict()
        if (cedict.isSuccess) {
            app.getSharedPreferences(OpenReaderApp.DICT_PREFS, android.content.Context.MODE_PRIVATE)
                .edit()
                .putBoolean("hanzi_imported", true)
                .putBoolean("cedict_imported", true)
                .apply()
        }
        return if (cedict.isSuccess) {
            Result.success((hanzi.getOrNull() ?: 0) + (cedict.getOrNull() ?: 0))
        } else {
            cedict
        }
    }

    // ------------------------------------------------------------------ cloud

    val cloudManager: CloudManager = CloudManager(app)
    val downloader = DictionaryDownloader(OkHttpClient.Builder().callTimeout(300, TimeUnit.SECONDS).build())

    private val _cloudVersion = MutableStateFlow(0)
    val cloudVersion: StateFlow<Int> = _cloudVersion.asStateFlow()

    fun cloudCredentials(type: CloudType): Pair<String, String> = cloudManager.credentials(type)

    fun saveCloudCredentials(type: CloudType, id: String, secret: String) {
        cloudManager.saveCredentials(type, id, secret)
        _cloudVersion.value++
    }

    fun isCloudConnected(type: CloudType): Boolean = cloudManager.isConnected(type)

    fun authUrl(type: CloudType): String? = cloudManager.authClient(type)?.authorizeUrl()

    /** Exchanges the code from the provider's sign-in page for a token. */
    suspend fun finishCloudAuth(type: CloudType, code: String): Result<Unit> {
        val client = cloudManager.authClient(type)
            ?: return Result.failure(IllegalStateException("请先填写 ${type.label} 的 AppKey/Secret"))
        return client.exchangeToken(code).map {
            cloudManager.saveToken(type, it)
            _cloudVersion.value++
        }
    }

    fun disconnectCloud(type: CloudType) {
        cloudManager.disconnect(type)
        _cloudVersion.value++
    }

    // ------------------------------------------------------------- dict packs

    /** Downloads a pack and imports it; progress 0-100 then the entry count. */
    suspend fun downloadPack(
        pack: DictionaryPack,
        mirrorUrl: String? = null,
        onProgress: (Int) -> Unit
    ): Result<Int> {
        val file = File(app.cacheDir, "dict_${pack.id}.${pack.format.name.lowercase()}")
        val downloaded = downloader.download(pack, file, mirrorUrl, onProgress)
            .getOrElse { return Result.failure(it) }
        val result = when (pack.format) {
            PackFormat.CSV -> dictionary.importCsv(downloaded)
            PackFormat.JSON -> dictionary.importJsonFile(downloaded)
        }
        runCatching { downloaded.delete() }
        return result
    }

    suspend fun syncUpload(): Result<Unit> {
        val json = syncManager.exportJson()
        return WebDavSync(settings.value).upload(SyncManager.FILE_NAME, json)
    }

    suspend fun syncDownload(): Result<Int> {
        val json = WebDavSync(settings.value).download(SyncManager.FILE_NAME).getOrElse {
            return Result.failure(it)
        }
        return syncManager.importJson(json)
    }

    suspend fun exportText(): String = syncManager.exportJson()

    // ------------------------------------------------------------- auto-update

    fun checkForUpdate() {
        viewModelScope.launch {
            _updateState.value = UpdateState.Checking
            Updater.latestRelease()
                .onSuccess { info ->
                    if (Updater.isNewer(info.version, BuildConfig.VERSION_NAME) && info.apkUrl != null) {
                        _updateState.value = UpdateState.Available(info)
                    } else {
                        _updateState.value = UpdateState.UpToDate(info.version)
                    }
                }
                .onFailure {
                    _updateState.value = UpdateState.Error(it.message ?: "检查更新失败")
                }
        }
    }

    fun downloadAndInstall(info: ReleaseInfo) {
        viewModelScope.launch {
            _updateState.value = UpdateState.Downloading(0)
            Updater.downloadApk(app, info.apkUrl!!) { progress ->
                _updateState.value = UpdateState.Downloading(progress)
            }.onSuccess { file ->
                _updateState.value = UpdateState.Ready(file)
                Updater.installApk(app, file)
                _updateState.value = UpdateState.Installed
            }.onFailure {
                _updateState.value = UpdateState.Error(it.message ?: "下载失败")
            }
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateState.Idle
    }
}
