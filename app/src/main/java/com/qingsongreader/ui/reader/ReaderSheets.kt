package com.qingsongreader.ui.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingsongreader.data.db.BookmarkEntity
import com.qingsongreader.data.db.HighlightEntity
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.TextAlignSetting
import com.qingsongreader.data.prefs.ThemeMode
import com.qingsongreader.data.prefs.ViewMode
import com.qingsongreader.data.repo.SearchHit
import com.qingsongreader.reader.epub.TocEntry
import kotlinx.coroutines.launch

enum class ReaderSheet { NONE, TOC, SETTINGS, ANNOTATIONS, SEARCH, DICTIONARY }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TocSheet(
    toc: List<TocEntry>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            items(toc) { entry ->
                TocRow(entry = entry, depth = 0, onSelect = onSelect)
            }
        }
    }
}

@Composable
private fun TocRow(entry: TocEntry, depth: Int, onSelect: (String) -> Unit) {
    Column {
        Text(
            text = entry.title.ifBlank { "(无标题)" },
            modifier = Modifier
                .fillMaxWidth()
                .clickable { if (entry.href.isNotBlank()) onSelect(entry.href) }
                .padding(start = (16 + depth * 16).dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        entry.children.forEach { TocRow(it, depth + 1, onSelect) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("排版", style = MaterialTheme.typography.titleLarge)
            Text("字号：${settings.fontSizeSp} sp", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.fontSizeSp.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(fontSizeSp = it.toInt())) },
                valueRange = 12f..36f
            )

            Text("行距：${String.format("%.1f", settings.lineHeight)}", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.lineHeight,
                onValueChange = { onSettingsChange(settings.copy(lineHeight = it)) },
                valueRange = 1.0f..2.4f
            )

            Text("页边距：${settings.pageMarginDp} dp", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.pageMarginDp.toFloat(),
                onValueChange = { onSettingsChange(settings.copy(pageMarginDp = it.toInt())) },
                valueRange = 4f..48f
            )

            Text("字体", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "默认", "serif" to "衬线", "sans" to "黑体", "mono" to "等宽").forEach { (value, label) ->
                    FilterChip(
                        selected = settings.fontFamily == value,
                        onClick = { onSettingsChange(settings.copy(fontFamily = value)) },
                        label = { Text(label) }
                    )
                }
            }

            Text("主题", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.LIGHT to "明亮", ThemeMode.SEPIA to "纸色", ThemeMode.DARK to "夜间").forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { onSettingsChange(settings.copy(themeMode = mode)) },
                        label = { Text(label) }
                    )
                }
            }

            Text("翻页", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.viewMode == ViewMode.PAGED,
                    onClick = { onSettingsChange(settings.copy(viewMode = ViewMode.PAGED)) },
                    label = { Text("分页") }
                )
                FilterChip(
                    selected = settings.viewMode == ViewMode.SCROLLED,
                    onClick = { onSettingsChange(settings.copy(viewMode = ViewMode.SCROLLED)) },
                    label = { Text("滚动") }
                )
                FilterChip(
                    selected = settings.textAlign == TextAlignSetting.JUSTIFY,
                    onClick = { onSettingsChange(settings.copy(textAlign = TextAlignSetting.JUSTIFY)) },
                    label = { Text("两端对齐") }
                )
                FilterChip(
                    selected = settings.textAlign == TextAlignSetting.LEFT,
                    onClick = { onSettingsChange(settings.copy(textAlign = TextAlignSetting.LEFT)) },
                    label = { Text("左对齐") }
                )
            }

            Text("朗读语速：${String.format("%.1f", settings.ttsRate)}×", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.ttsRate,
                onValueChange = { onSettingsChange(settings.copy(ttsRate = (it * 10).toInt() / 10f)) },
                valueRange = 0.5f..2.5f,
                steps = 19
            )
            Text("朗读音调：${String.format("%.1f", settings.ttsPitch)}×", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = settings.ttsPitch,
                onValueChange = { onSettingsChange(settings.copy(ttsPitch = (it * 10).toInt() / 10f)) },
                valueRange = 0.5f..2.0f,
                steps = 14
            )

            Row(modifier = Modifier.padding(bottom = 24.dp)) {
                TextButton(onClick = onDismiss) { Text("完成") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsSheet(
    highlights: List<HighlightEntity>,
    bookmarks: List<BookmarkEntity>,
    onJump: (chapterIndex: Int, offset: Int) -> Unit,
    onDeleteHighlight: (String) -> Unit,
    onEditNote: (String, String) -> Unit,
    onDeleteBookmark: (String) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit
) {
    var editing by remember { mutableStateOf<HighlightEntity?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text("批注与书签", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onExport) { Text("导出") }
                }
            }
            items(bookmarks) { bookmark ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJump(bookmark.chapterIndex, bookmark.offset) }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("🔖 ${bookmark.label}", style = MaterialTheme.typography.bodyMedium)
                    }
                    IconButton(onClick = { onDeleteBookmark(bookmark.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除书签")
                    }
                }
            }
            items(highlights) { highlight ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = highlight.text,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onJump(highlight.chapterIndex, highlight.startOffset) }
                        )
                        if (highlight.note.isNotBlank()) {
                            Text(
                                text = "笔记：${highlight.note}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        TextButton(onClick = { editing = highlight }) { Text("编辑笔记") }
                    }
                    IconButton(onClick = { onDeleteHighlight(highlight.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "删除高亮")
                    }
                }
            }
        }
    }

    editing?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.note) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("笔记") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    onEditNote(target.id, text)
                    editing = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("取消") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    hits: List<SearchHit>,
    onQueryChange: (String) -> Unit,
    onJump: (SearchHit) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onQueryChange(it)
                },
                label = { Text("全书搜索") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                items(hits) { hit ->
                    Text(
                        text = hit.snippet,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onJump(hit) }
                            .padding(vertical = 8.dp),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

