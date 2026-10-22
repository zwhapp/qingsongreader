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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class SettingsViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository(app)
    private val syncManager = SyncManager(app)
    private val dictionary = DictionaryRepository(app)

    init {
        // Keep the updater's mirror prefix in sync with the stored setting.
        viewModelScope.launch {
            repository.settings.collect { Updater.mirror = it.githubMirror.trim() }
        }
    }

    val settings: StateFlow<ReaderSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    /**
     * Update state lives in the Updater singleton so the APK download keeps
     * running (background + resumable) no matter where the user navigates.
     */
    val updateState: StateFlow<Updater.UpdateState> = Updater.state

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

    fun checkForUpdate() = Updater.check()

    fun downloadAndInstall(info: ReleaseInfo) = Updater.downloadAndInstall(app, info)

    fun dismissUpdate() = Updater.reset()
}
