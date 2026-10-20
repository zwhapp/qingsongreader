package com.qingsongreader.data.repo

import android.content.Context
import android.net.Uri
import com.qingsongreader.data.db.BookEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Application-scoped import queue. Imports run here instead of a ViewModel so
 * they keep going when the user navigates away or leaves the app entirely:
 * the shelf screen just observes [active] and draws a progress bar.
 */
object ImportManager {

    data class ActiveImport(
        val name: String,
        val received: Long,
        val total: Long
    ) {
        /** 0-99, or -1 when the source size is unknown (indeterminate). */
        val percent: Int
            get() = if (total > 0) ((received * 100) / total).toInt().coerceIn(0, 99) else -1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _active = MutableStateFlow<List<ActiveImport>>(emptyList())
    val active: StateFlow<List<ActiveImport>> = _active.asStateFlow()

    fun importBook(
        context: Context,
        uri: Uri,
        suggestedName: String? = null,
        onResult: (Result<BookEntity>) -> Unit = {}
    ) {
        val repository = BookRepository(context)
        val display = suggestedName?.takeIf { it.isNotBlank() } ?: "图书"
        fun update(received: Long, total: Long) {
            _active.value = _active.value.map {
                if (it.name == display) it.copy(received = received, total = total) else it
            }
        }
        _active.value = _active.value + ActiveImport(display, 0, -1)
        scope.launch {
            val result = runCatching { repository.import(uri, suggestedName, ::update) }
            _active.value = _active.value.filter { it.name != display }
            onResult(result)
        }
    }
}
