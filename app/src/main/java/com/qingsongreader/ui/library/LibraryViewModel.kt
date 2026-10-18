package com.qingsongreader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.repo.BookRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = BookRepository(application)

    val books: StateFlow<List<BookEntity>> = repository.books()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun import(uri: Uri, suggestedName: String? = null, onResult: (Result<BookEntity>) -> Unit = {}) {
        viewModelScope.launch {
            onResult(runCatching { repository.import(uri, suggestedName) })
        }
    }

    fun delete(book: BookEntity) {
        viewModelScope.launch { repository.delete(book.id) }
    }
}
