package com.qingsongreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.repo.BookRepository
import com.qingsongreader.data.repo.ImportManager
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BookRepository(application)

    val books: StateFlow<List<BookEntity>> = repository.books()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun import(uri: Uri, suggestedName: String? = null, onResult: (Result<BookEntity>) -> Unit = {}) {
        // Runs on the application-scoped ImportManager, so the copy keeps
        // going while the user navigates away or leaves the app.
        ImportManager.importBook(getApplication(), uri, suggestedName, onResult)
    }

    /**
     * Imports a file already on disk (WebDAV download, WeChat download folder,
     * ...). Returns the imported book through [onResult].
     */
    fun importFile(source: File, displayName: String, onResult: (Result<BookEntity>) -> Unit = {}) {
        viewModelScope.launch {
            onResult(runCatching { repository.importFile(source, displayName) })
        }
    }

    fun delete(book: BookEntity) {
        viewModelScope.launch { repository.delete(book.id) }
    }
}
