package com.qingsongreader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingsongreader.sync.WebDavSync
import kotlinx.coroutines.launch
import java.io.File

/** Book file extensions the importers recognise. */
val BOOK_EXTENSIONS = setOf("epub", "pdf", "txt", "md", "mobi", "azw3")

private fun isBookFile(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase() in BOOK_EXTENSIONS

/**
 * Browses the user's own WebDAV server (the same account configured in
 * Settings → Sync) and downloads the picked book into the app's cache before
 * importing it into the library.
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
    var path by remember { mutableStateOf("") } // relative dir, "" = root
    var entries by remember { mutableStateOf<List<WebDavSync.WebDavItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0) }

    fun load(p: String) {
        scope.launch {
            entries = null
            error = null
            dav.list(p).fold(
                onSuccess = { list ->
                    // Hide the directory's own "." entry (href of the folder itself).
                    entries = list.filter { it.name != "" }
                },
                onFailure = { error = it.message ?: "无法连接 WebDAV" }
            )
        }
    }

    LaunchedEffect(path) { load(path) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("从我的 WebDAV 导入", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "使用设置里配置的服务：${url.trimEnd('/')}/" + if (path.isBlank()) "" else "/$path",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (path.isNotBlank()) {
                TextButton(onClick = { path = path.substringBeforeLast('/', "") }) { Text("返回上一级") }
            }

            when {
                error != null -> Column {
                    Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 12.dp))
                    Button(onClick = { load(path) }) { Text("重试") }
                }
                entries == null -> Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalArrangement = Arrangement.Center
                ) { CircularProgressIndicator() }
                else -> LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    items(entries!!.sortedWith(
                        compareByDescending<WebDavSync.WebDavItem> { it.isDir }.thenBy { it.name.lowercase() }
                    )) { item ->
                        val name = item.name
                        if (item.isDir) {
                            Text(
                                text = "📁 $name",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { path = if (path.isBlank()) name else "$path/$name" }
                                    .padding(vertical = 10.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        } else if (isBookFile(name)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = downloading == null) {
                                        downloading = name
                                        progress = 0
                                        scope.launch {
                                            val dest = File.createTempFile("webdav-", "." + name.substringAfterLast('.', "epub"))
                                            dav.downloadFile(
                                                if (path.isBlank()) name else "$path/$name",
                                                dest
                                            ) { p -> progress = p }.fold(
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
                                Text(
                                    text = "📄 $name",
                                    modifier = Modifier.weight(1f),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text = formatSize(item.size),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
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

/**
 * Lists book files WeChat users commonly receive: the "Open with other app →
 * save" landing folders and WeChat's own download dirs. Android 11+ hides
 * Android/data from other apps, so those folders are silently skipped when
 * unreadable. A SAF fallback is offered for everything else.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeixinImportSheet(
    onImportFile: (File, String) -> Unit,
    onPickDocument: () -> Unit,
    onDismiss: () -> Unit
) {
    val files = remember {
        sequenceOf(
            "/sdcard/Download/WeiXin",
            "/sdcard/Android/data/com.tencent.mm/MicroMsg/Download",
            "/sdcard/Download"
        ).flatMap { dir ->
            runCatching {
                File(dir).listFiles { f -> f.isFile && isBookFile(f.name) }?.toList().orEmpty()
            }.getOrDefault(emptyList())
        }
            .distinctBy { it.absolutePath }
            .sortedByDescending { it.lastModified() }
            .take(60)
            .toList()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("从微信文件导入", style = MaterialTheme.typography.titleMedium)
            Text(
                "推荐：在微信里长按文件 →「用其他应用打开」→ 轻松阅读，直接导入。若已保存到手机，下方会列出常见下载目录中的图书文件。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (files.isEmpty()) {
                Text(
                    "未在常见目录找到图书文件。",
                    modifier = Modifier.padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    items(files) { f ->
                        Text(
                            text = "📄 ${f.name}（${f.parentFile?.name ?: ""} · ${formatSize(f.length())}）",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onImportFile(f, f.nameWithoutExtension) }
                                .padding(vertical = 10.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onPickDocument) { Text("浏览文件选择器") }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
