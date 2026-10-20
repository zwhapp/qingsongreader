package com.qingsongreader.ui.settings

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingsongreader.BuildConfig
import com.qingsongreader.OpenReaderApp
import com.qingsongreader.features.update.Updater
import com.qingsongreader.features.update.Updater.ReleaseInfo
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.dict.DictionaryRepository
import com.qingsongreader.sync.ImportResult
import com.qingsongreader.sync.SyncManager
import com.qingsongreader.sync.WebDavSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

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

    // ------------------------------------------------------------- dict packs

    

    /**
     * Uploads the library JSON plus every book file that still exists locally.
     * Credentials are passed explicitly so the sync never races the DataStore
     * write the settings screen just performed.
     */
    suspend fun syncUpload(url: String, user: String, password: String): Result<Int> {
        val json = syncManager.exportJson()
        val dav = WebDavSync(url, user, password)
        dav.upload(SyncManager.FILE_NAME, json).getOrElse { return Result.failure(it) }
        var uploaded = 0
        val failures = ArrayList<String>()
        syncManager.bookFiles().forEach { (book, file) ->
            dav.uploadFile(syncManager.remoteBookName(book), file)
                .onSuccess { uploaded++ }
                .onFailure { failures += book.title }
        }
        return if (failures.isNotEmpty()) {
            Result.failure(
                IllegalStateException(
                    "阅读数据已上传，但 ${failures.size} 个书籍文件失败：" +
                        failures.take(3).joinToString("、") + "…"
                )
            )
        } else {
            Result.success(uploaded)
        }
    }

    /**
     * Restores the library: imports the JSON and re-downloads every book file
     * that is missing locally. Books whose file cannot be fetched are skipped
     * instead of being imported as broken records.
     */
    suspend fun syncDownload(url: String, user: String, password: String): Result<ImportResult> {
        val dav = WebDavSync(url, user, password)
        val json = dav.download(SyncManager.FILE_NAME).getOrElse { return Result.failure(it) }
        return syncManager.importJson(json) { book ->
            val local = syncManager.localBookFile(book)
            local.exists() || dav.downloadFile(syncManager.remoteBookName(book), local).isSuccess
        }
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
