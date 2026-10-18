package com.qingsongreader.ui.stats

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qingsongreader.OpenReaderApp
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.db.SessionEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookStat(val title: String, val minutes: Long)
data class StatsState(
    val totalMinutes: Long = 0,
    val todayMinutes: Long = 0,
    val perBook: List<BookStat> = emptyList()
)

class StatsViewModel(application: Application) : AndroidViewModel(application) {

    private val db = OpenReaderApp.instance.database
    private val _state = MutableStateFlow(StatsState())
    val state: StateFlow<StatsState> = _state.asStateFlow()

    private val sessions: StateFlow<List<SessionEntity>> = db.statsDao().observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        viewModelScope.launch {
            sessions.collect { list ->
                val books: List<BookEntity> = db.bookDao().all()
                val titles = books.associateBy { it.id }
                    val startOfDay = java.util.Calendar.getInstance().apply {
                        set(java.util.Calendar.HOUR_OF_DAY, 0)
                        set(java.util.Calendar.MINUTE, 0)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    val total = list.sumOf { it.durationMs }
                    val today = list.filter { it.startedAt >= startOfDay }.sumOf { it.durationMs }
                    val perBook = list.groupBy { it.bookId }
                        .map { (bookId, items) ->
                            BookStat(
                                title = titles[bookId]?.title ?: "已删除的书",
                                minutes = items.sumOf { it.durationMs } / 60000
                            )
                        }
                        .sortedByDescending { it.minutes }
                    _state.value = StatsState(
                        totalMinutes = total / 60000,
                        todayMinutes = today / 60000,
                        perBook = perBook
                    )
                }
        }
    }
}
