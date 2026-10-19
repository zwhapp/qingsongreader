package com.qingsongreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingsongreader.sync.WebDavSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Book file extensions the importers recognise. */
val BOOK_EXTENSIONS = setOf("epub", "pdf", "txt", "md", "mobi", "azw3")

private fun isBookFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS

/**
 * Browses the user's own WebDAV server (the same account configured in
 * Settings → Sync). On open it walks the directory tree automatically and
 * lists every book file it finds — no manual folder digging. Tapping an
 * entry downloads it into the cache and imports it into the library.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebDavImportSheet(
    url: String,
    user: String,
    password: String,
    onImportFile: (File, String) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val dav = remember(url, user, password) { WebDavSync(url, user, password) }
    var attempt by remember { mutableIntStateOf(0) }
    var books by remember { mutableStateOf<List<Pair<String, Long>>?>(null) } // path to size
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }

    // Walk the tree once per open/retry: depth-first, bounded, sequential
    // (WebDAV servers dislike parallel PROPFIND bursts).
    LaunchedEffect(url, user, password, attempt) {
        books = null
        error = null
        withContext(Dispatchers.IO) {
            val found = ArrayList<Pair<String, Long>>()
            val visited = HashSet<String>()
            var failure: String? = null
            suspend fun walk(dir: String, depth: Int) {
                if (depth > 4 || found.size >= 120) return
                val key = dir.trim('/')
                if (!visited.add(key)) return
                val entries = dav.list(key).getOrElse { throwable ->
                    failure = throwable.message ?: "无法连接 WebDAV"
                    return
                }
                for (item in entries) {
                    if (item.name.isBlank()) continue // the directory's own href
                    val child = if (key.isBlank()) item.name else "$key/${item.name}"
                    if (item.isDir) {
                        walk(child, depth + 1)
                    } else if (isBookFile(item.name)) {
                        found.add(child to item.size)
                        if (found.size >= 120) return
                    }
                }
            }
            runCatching { walk("", 0) }
                .onFailure { failure = it.message ?: "无法连接 WebDAV" }
            books = found.toList()
            error = failure
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("从我的 WebDAV 导入", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "使用设置里配置的服务：${url.trimEnd('/')}/，已自动搜索全部目录中的图书文件。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )

            when {
                error != null -> Column {
                    Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        "请检查设置里的 WebDAV 地址与账号；也可以改用「网盘导入」或「从本地文件导入」。",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = { attempt++ }) { Text("重新搜索") }
                }
                books == null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
                books!!.isEmpty() -> Text(
                    "没有找到图书文件（支持 EPUB / PDF / TXT / MD / MOBI / AZW3）。",
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
                else -> LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    items(books!!) { (path, size) ->
                        val name = path.substringAfterLast('/')
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = downloading == null) {
                                    downloading = name
                                    progress = 0
                                    scope.launch {
                                        val dest = File.createTempFile("webdav-", "." + name.substringAfterLast('.', "epub"))
                                        dav.downloadFile(path, dest) { p -> progress = p }.fold(
                                            onSuccess = {
                                                downloading = null
                                                onImportFile(it, name.substringBeforeLast('.'))
                                            },
                                            onFailure = {
                                                downloading = null
                                                error = it.message ?: "下载失败"
                                            }
                                        )
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "📄 $name",
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = path.substringBeforeLast('/'),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(text = formatSize(size), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            downloading?.let { name ->
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text("正在下载：$name（$progress%）", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 4.dp)) { Text("关闭") }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
