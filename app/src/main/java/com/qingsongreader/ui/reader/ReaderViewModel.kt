package com.qingsongreader.ui.reader

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.qingsongreader.ai.AiClient
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.db.BookmarkEntity
import com.qingsongreader.data.db.HighlightEntity
import com.qingsongreader.data.db.SessionEntity
import com.qingsongreader.OpenReaderApp
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.data.prefs.ViewMode
import com.qingsongreader.data.repo.BookRepository
import com.qingsongreader.data.repo.SearchHit
import com.qingsongreader.dict.DictEntry
import com.qingsongreader.dict.DictionaryRepository
import com.qingsongreader.reader.PdfContent
import com.qingsongreader.reader.ReaderContent
import com.qingsongreader.reader.epub.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class ReaderState(
    val loading: Boolean = true,
    val title: String = "",
    val author: String = "",
    val isPdf: Boolean = false,
    val chapterIndex: Int = 0,
    val chapterCount: Int = 1,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val progress: Float = 0f,
    val toc: List<TocEntry> = emptyList(),
    val highlights: List<HighlightEntity> = emptyList(),
    val bookmarks: List<BookmarkEntity> = emptyList(),
    val searchHits: List<SearchHit> = emptyList(),
    val query: String = "",
    val selection: SelectionRange? = null,
    val dictWord: String = "",
    val dictEntry: DictEntry? = null,
    val dictLoading: Boolean = false,
    val dictError: String? = null,
    val message: String? = null
)

class ReaderViewModel(
    application: Application,
    private val bookId: String
) : AndroidViewModel(application) {

    private val repository = BookRepository(application)
    private val settingsRepository = SettingsRepository(application)
    private val dictionary = DictionaryRepository(application)
    private val db = OpenReaderApp.instance.database

    val settings: StateFlow<ReaderSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ReaderSettings())

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state.asStateFlow()

    private var book: BookEntity? = null
    private var content: ReaderContent? = null
    private var sessionStart: Long = 0L

    init {
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { repository.get(bookId) }
            if (loaded == null) {
                _state.value = ReaderState(loading = false, message = "找不到这本书")
                return@launch
            }
            book = loaded
            // open() may self-heal the record (e.g. an EPUB wrongly stored as TXT
            // before content sniffing existed); re-read so later persist() calls
            // write the corrected format/filePath instead of the stale ones.
            book = withContext(Dispatchers.IO) { repository.get(bookId) } ?: loaded
            val opened = runCatching {
                withContext(Dispatchers.IO) { repository.open(book ?: loaded) }
            }.getOrElse { error ->
                _state.value = ReaderState(loading = false, message = "无法打开：${error.message ?: "文件缺失或已损坏"}")
                return@launch
            }
            content = opened
            sessionStart = System.currentTimeMillis()
            _state.value = _state.value.copy(
                loading = false,
                title = loaded.title,
                author = loaded.author,
                isPdf = opened is PdfContent,
                chapterIndex = loaded.chapterIndex.coerceIn(0, (opened.chapterCount - 1).coerceAtLeast(0)),
                chapterCount = opened.chapterCount.coerceAtLeast(1),
                pageIndex = loaded.pageIndex,
                progress = loaded.progress,
                toc = opened.toc
            )

            db.annotationDao().observeHighlights(bookId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
                .collect { list -> _state.value = _state.value.copy(highlights = list) }
        }

        viewModelScope.launch {
            db.annotationDao().observeBookmarks(bookId)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
                .collect { list -> _state.value = _state.value.copy(bookmarks = list) }
        }
    }

    // ---------------------------------------------------------------- content

    suspend fun renderChapter(index: Int, settings: ReaderSettings): Pair<String, String> =
        withContext(Dispatchers.IO) {
            val c = content ?: return@withContext ("" to "file:///")
            val html = c.chapterHtml(index, settings)
            val base = c.chapterBaseUrl(index)
            html to base
        }

    fun chapterTitle(index: Int): String = content?.chapterTitle(index).orEmpty()

    fun isPaged(settings: ReaderSettings): Boolean = settings.viewMode == ViewMode.PAGED

    fun hrefToChapter(href: String): Int = content?.hrefToChapter(href) ?: -1

    fun currentContent(): ReaderContent? = content

    fun currentBook(): BookEntity? = book

    // ------------------------------------------------------------- navigation

    fun goToChapter(index: Int) {
        val clamped = index.coerceIn(0, (_state.value.chapterCount - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(chapterIndex = clamped, pageIndex = 0)
        persist()
    }

    fun goToPdfPage(page: Int) {
        val clamped = page.coerceIn(0, (_state.value.chapterCount - 1).coerceAtLeast(0))
        _state.value = _state.value.copy(
            chapterIndex = clamped,
            progress = (clamped + 1f) / _state.value.chapterCount.toFloat()
        )
        persist()
    }

    fun onPageChanged(page: Int, pageCount: Int, chapterProgress: Float) {
        val total = _state.value.chapterCount.coerceAtLeast(1)
        val globalProgress = if (_state.value.isPdf) {
            (page + 1f) / total
        } else {
            ((_state.value.chapterIndex + chapterProgress.coerceIn(0f, 1f)) / total.toFloat())
                .coerceIn(0f, 1f)
        }
        _state.value = _state.value.copy(
            pageIndex = page,
            pageCount = pageCount.coerceAtLeast(1),
            progress = globalProgress
        )
        persist()
    }

    private fun persist() {
        val b = book ?: return
        val s = _state.value
        val updated = b.copy(
            chapterIndex = s.chapterIndex,
            pageIndex = s.pageIndex,
            progress = s.progress,
            lastReadAt = System.currentTimeMillis()
        )
        book = updated
        viewModelScope.launch(Dispatchers.IO) { repository.save(updated) }
    }

    // ----------------------------------------------------------- annotations

    fun setSelection(range: SelectionRange?) {
        _state.value = _state.value.copy(selection = range)
    }

    /** Adds a highlight and returns its id, so the caller can paint it immediately. */
    fun addHighlight(range: SelectionRange, color: String): String {
        val id = UUID.randomUUID().toString()
        val entity = HighlightEntity(
            id = id,
            bookId = bookId,
            chapterIndex = _state.value.chapterIndex,
            startOffset = range.start,
            endOffset = range.end,
            text = range.text,
            color = color
        )
        viewModelScope.launch(Dispatchers.IO) { db.annotationDao().upsert(entity) }
        _state.value = _state.value.copy(
            selection = null,
            message = "已添加高亮"
        )
        return id
    }

    fun updateNote(id: String, note: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = db.annotationDao().highlightsOf(bookId)
            list.firstOrNull { it.id == id }?.let { db.annotationDao().upsert(it.copy(note = note)) }
        }
    }

    fun removeHighlight(id: String) {
        viewModelScope.launch(Dispatchers.IO) { db.annotationDao().deleteHighlight(id) }
    }

    fun highlightsInChapter(index: Int): List<HighlightEntity> =
        _state.value.highlights.filter { it.chapterIndex == index }

    fun addBookmark(label: String = "") {
        val entity = BookmarkEntity(
            id = UUID.randomUUID().toString(),
            bookId = bookId,
            chapterIndex = _state.value.chapterIndex,
            offset = _state.value.pageIndex,
            label = label.ifBlank { chapterTitle(_state.value.chapterIndex) }
        )
        viewModelScope.launch(Dispatchers.IO) { db.annotationDao().upsert(entity) }
        _state.value = _state.value.copy(message = "已添加书签")
    }

    fun removeBookmark(id: String) {
        viewModelScope.launch(Dispatchers.IO) { db.annotationDao().deleteBookmark(id) }
    }

    fun consumeMessage() {
        _state.value = _state.value.copy(message = null)
    }

    // ---------------------------------------------------------------- search

    fun search(query: String) {
        viewModelScope.launch {
            val c = content ?: return@launch
            val hits = repository.search(c, query)
            _state.value = _state.value.copy(searchHits = hits, query = query)
        }
    }

    fun clearSearch() {
        _state.value = _state.value.copy(searchHits = emptyList(), query = "")
    }

    // -------------------------------------------------------------------- ai

    private fun aiClient(): AiClient = AiClient(settings.value)

    suspend fun translate(text: String, targetLanguage: String): Result<String> =
        aiClient().translate(text, targetLanguage)

    suspend fun summarize(text: String): Result<String> = aiClient().summarize(text)

    suspend fun ask(question: String, context: String): Result<String> = aiClient().ask(question, context)

    // ------------------------------------------------------------- dictionary

    /** Looks a word up: offline pack -> free online dictionary -> AI fallback. */
    fun lookup(word: String) {
        val query = word.trim().take(60)
        if (query.isBlank()) return
        viewModelScope.launch {
            _state.value = _state.value.copy(
                dictWord = query,
                dictLoading = true,
                dictError = null,
                dictEntry = null
            )
            dictionary.lookup(query) { aiClient().explain(it) }
                .fold(
                    onSuccess = { entry ->
                        _state.value = _state.value.copy(dictEntry = entry, dictLoading = false, dictError = null)
                    },
                    onFailure = { throwable ->
                        _state.value = _state.value.copy(
                            dictLoading = false,
                            dictError = throwable.message ?: "查询失败"
                        )
                    }
                )
        }
    }

    fun clearDict() {
        _state.value = _state.value.copy(dictWord = "", dictEntry = null, dictError = null, dictLoading = false)
    }

    // ------------------------------------------------------------------ misc

    /** Exports highlights and notes as Markdown. */
    suspend fun exportAnnotations(): String = withContext(Dispatchers.IO) {
        val list = db.annotationDao().highlightsOf(bookId)
        if (list.isEmpty()) return@withContext "（暂无批注）"
        buildString {
            append("# 《${book?.title.orEmpty()}》批注导出\n\n")
            list.sortedWith(compareBy({ it.chapterIndex }, { it.startOffset })).forEach { h ->
                append("> ${h.text}\n\n")
                if (h.note.isNotBlank()) append("${h.note}\n\n")
                append("— 第 ${h.chapterIndex + 1} 章\n\n")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val duration = System.currentTimeMillis() - sessionStart
        if (duration > 1000 && book != null) {
            val session = SessionEntity(
                id = UUID.randomUUID().toString(),
                bookId = bookId,
                startedAt = sessionStart,
                endedAt = System.currentTimeMillis(),
                durationMs = duration,
                pages = _state.value.pageIndex + 1
            )
            kotlinx.coroutines.runBlocking(Dispatchers.IO) { db.statsDao().upsert(session) }
        }
    }
}

class ReaderViewModelFactory(
    private val application: Application,
    private val bookId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return ReaderViewModel(application, bookId) as T
    }
}
