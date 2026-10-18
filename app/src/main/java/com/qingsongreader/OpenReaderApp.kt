package com.qingsongreader

import android.app.Application
import androidx.room.Room
import com.qingsongreader.data.db.AppDatabase
import com.qingsongreader.dict.DictionaryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OpenReaderApp : Application() {

    lateinit var database: AppDatabase
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        database = Room.databaseBuilder(applicationContext, AppDatabase::class.java, "openreader.db")
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .fallbackToDestructiveMigration()
            .build()
        importBuiltInDictionary()
    }

    /** Installs the shipped dictionaries once, in the background. */
    private fun importBuiltInDictionary() {
        val prefs = getSharedPreferences(DICT_PREFS, MODE_PRIVATE)
        CoroutineScope(Dispatchers.IO).launch {
            val repository = DictionaryRepository(this@OpenReaderApp)
            if (!prefs.getBoolean("hanzi_imported", false)) {
                if (repository.importBuiltInHanzi().isSuccess) {
                    prefs.edit().putBoolean("hanzi_imported", true).apply()
                }
            }
            if (!prefs.getBoolean("cedict_imported", false)) {
                if (repository.importBuiltInCedict().isSuccess) {
                    prefs.edit().putBoolean("cedict_imported", true).apply()
                }
            }
        }
    }

    companion object {
        const val DICT_PREFS = "dictionary"

        lateinit var instance: OpenReaderApp
            private set
    }
}
