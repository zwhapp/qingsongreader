package com.qingsongreader.ui.reader

import android.app.Activity
import android.app.Application
import android.graphics.Color
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.data.prefs.ViewMode
import com.qingsongreader.reader.ReaderScripts
import com.qingsongreader.tts.TtsController
import kotlinx.coroutines.launch
import java.io.File

private val HIGHLIGHT_COLORS = listOf("#FFE082", "#A5D6A7", "#90CAF9", "#F48FB1")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    bookId: String,
    settingsRepository: SettingsRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val application = context.applicationContext as Application
    val viewModel: ReaderViewModel = viewModel(factory = ReaderViewModelFactory(application, bookId))
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val controller = remember { ReaderWebController() }
    val tts = remember { TtsController(context) }
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var sheet by remember { mutableStateOf(ReaderSheet.NONE) }
    var chromeVisible by remember { mutableStateOf(true) }
    var pageCount by remember { mutableIntStateOf(1) }
    var sliderValue by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var jumpToChapterEnd by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose { tts.shutdown() }
    }

    DisposableEffect(settings.keepScreenOn) {
        if (settings.keepScreenOn) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    LaunchedEffect(state.progress) { sliderValue = state.progress }

    LaunchedEffect(state.selection, settings.autoLookup) {
        val selected = state.selection
        if (settings.autoLookup && selected != null && selected.text.isNotBlank() && selected.text.length <= 60) {
            viewModel.lookup(selected.text)
            sheet = ReaderSheet.DICTIONARY
        }
    }

    LaunchedEffect(state.message) {
        val message = state.message
        if (message != null) {
            snackbar.showSnackbar(message)
            viewModel.consumeMessage()
        }
    }

    // Speech rate / pitch changes take effect on the next utterance (and on an
    // ongoing one as soon as it finishes), so the sliders feel responsive.
    LaunchedEffect(settings.ttsRate) { tts.applyRate(settings.ttsRate) }
    LaunchedEffect(settings.ttsPitch) { tts.applyPitch(settings.ttsPitch) }

    /** Called by the WebView once a chapter finished loading. */
    val pageLoaded = remember { mutableStateOf<() -> Unit>({}) }

    val applySettings: (ReaderSettings) -> Unit = { next ->
        scope.launch { settingsRepository.update { next } }
    }

    fun refreshProgress() {
        if (viewModel.isPaged(settings)) {
            controller.currentPage { page ->
                controller.pageCount { count ->
                    pageCount = count
                    val chapterProgress = if (count > 1) page.toFloat() / (count - 1).toFloat() else 0f
                    viewModel.onPageChanged(page, count, chapterProgress)
                }
            }
        } else {
            controller.progress { value -> viewModel.onPageChanged(0, 1, value) }
        }
    }

    /** Moves to the previous/next chapter; shows a hint at the book boundaries. */
    fun turnChapter(delta: Int) {
        val target = state.chapterIndex + delta
        if (target in 0 until state.chapterCount) {
            // Paging backwards across a border should land on the previous
            // chapter's last page, so the reader continues where they left off.
            jumpToChapterEnd = delta < 0
            viewModel.goToChapter(target)
        } else {
            scope.launch {
                snackbar.showSnackbar(if (delta > 0) "已经是最后一章了" else "已经是第一章了")
            }
        }
    }

    fun turnPage(delta: Int) {
        if (state.isPdf) {
            // PDF: "pages" are chapters in the state model; clamp inside goToPdfPage.
            viewModel.goToPdfPage(state.chapterIndex + delta)
            return
        }
        if (settings.viewMode == ViewMode.PAGED) {
            controller.currentPage { page ->
                controller.pageCount { count ->
                    if (page + delta in 0 until count) {
                        controller.goToPage(page + delta)
                        refreshProgress()
                    } else {
                        // Chapter boundary: continue into the previous/next chapter.
                        turnChapter(delta)
                    }
                }
            }
        } else {
            // Scrolled mode: only cross the chapter border at the very top/bottom.
            controller.runJs(
                "(function(){var el=document.body;var max=el.scrollHeight-el.clientHeight;" +
                    "return (el.scrollTop<=1)?'top':((el.scrollTop>=max-1)?'bottom':'mid');})()"
            ) { raw ->
                val edge = raw.trim().trim('"')
                val atEdge = (delta > 0 && edge == "bottom") || (delta < 0 && edge == "top")
                if (atEdge) {
                    turnChapter(delta)
                } else {
                    controller.runJs("document.body.scrollTop += document.body.clientHeight * 0.9 * $delta") { refreshProgress() }
                }
            }
        }
    }

    LaunchedEffect(state.chapterIndex, state.highlights, settings.viewMode) {
        pageLoaded.value = {
            controller.runJs(ReaderScripts.RUNTIME)
            controller.setPaged(viewModel.isPaged(settings))
            viewModel.highlightsInChapter(state.chapterIndex).forEach { h ->
                controller.highlight(h.startOffset, h.endOffset, h.color, h.id)
            }
            if (jumpToChapterEnd) {
                // Entered the previous chapter from its border: show its last page.
                jumpToChapterEnd = false
                controller.pageCount { count ->
                    controller.goToPage((count - 1).coerceAtLeast(0))
                    refreshProgress()
                }
            } else {
                if (state.pageIndex > 0) controller.goToPage(state.pageIndex)
                refreshProgress()
            }
        }
    }

    val bookFile = remember(state.title) { viewModel.currentBook()?.let { File(it.filePath) } }

    LaunchedEffect(state.chapterIndex, settings, state.loading) {
        if (state.loading || state.isPdf) return@LaunchedEffect
        val (html, baseUrl) = viewModel.renderChapter(state.chapterIndex, settings)
        if (html.isBlank()) return@LaunchedEffect
        controller.loadChapter(html, baseUrl)
    }

    /** Horizontal swipe paging: only in paged mode, so scrolling mode keeps native scroll. */
    val swipeModifier = if (settings.viewMode == ViewMode.PAGED) {
        Modifier.pointerInput(Unit) {
            detectDragGestures(
                onDragEnd = {
                    if (dragX < -80) turnPage(1)
                    else if (dragX > 80) turnPage(-1)
                    dragX = 0f
                },
                onDrag = { _, amount -> dragX += amount.x }
            )
        }
    } else {
        Modifier
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (chromeVisible) {
                TopAppBar(
                    title = {
                        Text(
                            text = state.title.ifBlank { "阅读" },
                            maxLines = 1,
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                        }
                    },
                    actions = {
                        IconButton(onClick = { sheet = ReaderSheet.TOC }) {
                            Icon(Icons.Default.List, contentDescription = "目录")
                        }
                        IconButton(onClick = { sheet = ReaderSheet.SEARCH }) {
                            Icon(Icons.Default.Search, contentDescription = "搜索")
                        }
                        IconButton(onClick = { sheet = ReaderSheet.ANNOTATIONS }) {
                            Icon(Icons.Default.Bookmarks, contentDescription = "批注")
                        }
                        IconButton(onClick = { sheet = ReaderSheet.AI }) {
                            Icon(Icons.Default.SmartToy, contentDescription = "AI")
                        }
                        IconButton(onClick = { sheet = ReaderSheet.SETTINGS }) {
                            Icon(Icons.Default.FormatSize, contentDescription = "排版")
                        }
                    }
                )
            }
        },
        bottomBar = {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { turnPage(-1) }) { Text("上一页") }
                        Slider(
                            value = sliderValue,
                            onValueChange = { sliderValue = it },
                            onValueChangeFinished = {
                                val target = sliderValue.coerceIn(0f, 1f)
                                if (state.isPdf) {
                                    viewModel.goToPdfPage((target * state.chapterCount).toInt().coerceIn(0, state.chapterCount - 1))
                                } else {
                                    val chapter = (target * state.chapterCount).toInt()
                                        .coerceIn(0, (state.chapterCount - 1).coerceAtLeast(0))
                                    if (chapter != state.chapterIndex) viewModel.goToChapter(chapter)
                                    else controller.goToProgress(target * state.chapterCount - chapter)
                                }
                            },
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { turnPage(1) }) { Text("下一页") }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (state.isPdf) "第 ${state.chapterIndex + 1} / ${state.chapterCount} 页"
                            else "第 ${state.chapterIndex + 1} / ${state.chapterCount} 章",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = "${(state.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row {
                            TextButton(onClick = {
                                controller.pageText { text -> tts.speak(text, settings.ttsRate, settings.ttsPitch) }
                            }) {
                                Icon(Icons.Default.RecordVoiceOver, contentDescription = "朗读")
                                Text("朗读", modifier = Modifier.padding(start = 4.dp))
                            }
                            TextButton(onClick = { tts.stop() }) { Text("停止") }
                            TextButton(onClick = { viewModel.addBookmark() }) { Text("书签") }
                            TextButton(onClick = { chromeVisible = !chromeVisible }) {
                                Text(if (chromeVisible) "沉浸" else "工具栏")
                            }
                        }
                    }
                }
            }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LinearProgressIndicator()
                }
            } else if (state.isPdf) {
                val file = bookFile
                if (file != null && file.exists()) {
                    // Keyed by page so a page turn resets zoom/pan.
                    key(state.chapterIndex) {
                        PdfPageView(
                            file = file,
                            page = state.chapterIndex,
                            onTurnPage = { delta -> turnPage(delta) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("无法打开 PDF 文件")
                    }
                }
            } else {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(swipeModifier),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            layoutParams = ViewGroup.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                            setBackgroundColor(Color.TRANSPARENT)
                            this.settings.javaScriptEnabled = true
                            this.settings.allowFileAccess = true
                            this.settings.allowContentAccess = true
                            this.settings.loadWithOverviewMode = true
                            this.settings.useWideViewPort = true
                            this.settings.builtInZoomControls = true
                            this.settings.displayZoomControls = false
                            this.isHorizontalScrollBarEnabled = false
                            this.isVerticalScrollBarEnabled = false
                            this.settings.defaultTextEncodingName = "UTF-8"
                            addJavascriptInterface(
                                ReaderJsBridge(
                                    onLink = { href ->
                                        val index = viewModel.hrefToChapter(href)
                                        if (index >= 0) viewModel.goToChapter(index)
                                    },
                                    onSelection = { text ->
                                        if (text.isBlank()) {
                                            viewModel.setSelection(null)
                                        } else {
                                            controller.selectedRange { range ->
                                                viewModel.setSelection(range)
                                            }
                                        }
                                    },
                                    onTapZone = { side ->
                                        turnPage(if (side == "next") 1 else -1)
                                    }
                                ),
                                ReaderScripts.BRIDGE
                            )
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    pageLoaded.value.invoke()
                                }
                            }
                            controller.attach(this)
                        }
                    },
                    onRelease = { view ->
                        controller.detach()
                        view.destroy()
                    }
                )
            }

            // Selection toolbar
            state.selection?.let { selection ->
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(12.dp)
                        .background(
                            MaterialTheme.colorScheme.surface,
                            shape = MaterialTheme.shapes.medium
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HIGHLIGHT_COLORS.forEach { color ->
                        TextButton(onClick = {
                            val id = viewModel.addHighlight(selection, color)
                            controller.highlight(selection.start, selection.end, color, id)
                            controller.clearSelection()
                        }) {
                            Text("高亮", color = androidx.compose.ui.graphics.Color(
                                android.graphics.Color.parseColor(color)
                            ))
                        }
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(selection.text))
                        controller.clearSelection()
                        viewModel.setSelection(null)
                    }) { Text("复制") }
                    TextButton(onClick = {
                        tts.speak(selection.text, settings.ttsRate, settings.ttsPitch)
                    }) { Text("朗读") }
                    TextButton(onClick = {
                        viewModel.lookup(selection.text)
                        sheet = ReaderSheet.DICTIONARY
                    }) { Text("查词") }
                    TextButton(onClick = {
                        val intent = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse(
                                "https://dict.youdao.com/result?word=" +
                                    android.net.Uri.encode(selection.text.take(40)) +
                                    "&lang=en"
                            )
                        )
                        runCatching { context.startActivity(intent) }
                    }) { Text("网页") }
                    TextButton(onClick = { sheet = ReaderSheet.AI }) { Text("AI") }
                }
            }
        }
    }

    when (sheet) {
        ReaderSheet.NONE -> Unit
        ReaderSheet.TOC -> TocSheet(
            toc = state.toc,
            onSelect = { href ->
                val index = viewModel.hrefToChapter(href)
                if (index >= 0) viewModel.goToChapter(index)
                sheet = ReaderSheet.NONE
            },
            onDismiss = { sheet = ReaderSheet.NONE }
        )
        ReaderSheet.SETTINGS -> ReaderSettingsSheet(
            settings = settings,
            onSettingsChange = applySettings,
            onDismiss = { sheet = ReaderSheet.NONE }
        )
        ReaderSheet.ANNOTATIONS -> AnnotationsSheet(
            highlights = state.highlights,
            bookmarks = state.bookmarks,
            onJump = { chapterIndex, _ ->
                viewModel.goToChapter(chapterIndex)
                sheet = ReaderSheet.NONE
            },
            onDeleteHighlight = { id ->
                controller.removeHighlight(id)
                viewModel.removeHighlight(id)
            },
            onEditNote = viewModel::updateNote,
            onDeleteBookmark = viewModel::removeBookmark,
            onExport = {
                scope.launch {
                    val markdown = viewModel.exportAnnotations()
                    clipboard.setText(AnnotatedString(markdown))
                    snackbar.showSnackbar("批注已复制到剪贴板")
                }
            },
            onDismiss = { sheet = ReaderSheet.NONE }
        )
        ReaderSheet.SEARCH -> SearchSheet(
            hits = state.searchHits,
            onQueryChange = { query -> viewModel.search(query) },
            onJump = { hit ->
                viewModel.goToChapter(hit.chapterIndex)
                sheet = ReaderSheet.NONE
                if (state.query.isNotBlank()) controller.markSearch(state.query) {}
            },
            onDismiss = {
                viewModel.clearSearch()
                sheet = ReaderSheet.NONE
            }
        )
        ReaderSheet.AI -> AiSheet(
            selectedText = state.selection?.text.orEmpty(),
            onTranslate = { text, lang -> viewModel.translate(text, lang) },
            onSummarize = { text -> viewModel.summarize(text) },
            onAsk = { question, context -> viewModel.ask(question, context) },
            onDismiss = { sheet = ReaderSheet.NONE }
        )
        ReaderSheet.DICTIONARY -> DictionarySheet(
            word = state.dictWord,
            entry = state.dictEntry,
            loading = state.dictLoading,
            error = state.dictError,
            onSpeak = { tts.speak(state.dictWord, settings.ttsRate, settings.ttsPitch) },
            onDismiss = {
                sheet = ReaderSheet.NONE
                viewModel.clearDict()
            }
        )
    }
}
