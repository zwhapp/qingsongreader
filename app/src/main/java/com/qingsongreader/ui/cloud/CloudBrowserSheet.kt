package com.qingsongreader.ui.cloud

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qingsongreader.cloud.CloudFile
import com.qingsongreader.cloud.CloudManager
import com.qingsongreader.cloud.CloudType
import com.qingsongreader.data.repo.BookRepository
import kotlinx.coroutines.launch
import java.io.File

/** Browses a connected cloud drive and imports the picked book. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudBrowserSheet(
    type: CloudType,
    manager: CloudManager,
    onImported: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val repository = remember { BookRepository(context) }
    val scope = rememberCoroutineScope()

    var stack by remember { mutableStateOf(listOf(CloudFile.root(type))) }
    var files by remember { mutableStateOf<List<CloudFile>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load(folder: CloudFile) {
        scope.launch {
            loading = true
            error = null
            val client = manager.client(type)
            if (client == null) {
                loading = false
                error = "未连接 ${type.label}，请先在设置里授权"
                return@launch
            }
            client.list(folder).fold(
                onSuccess = { files = it; loading = false },
                onFailure = { error = it.message ?: "列表失败"; loading = false }
            )
        }
    }

    fun import(file: CloudFile) {
        scope.launch {
            val client = manager.client(type)
            if (client == null) {
                error = "未连接 ${type.label}"
                return@launch
            }
            status = "下载《${file.name}》…"
            val result = client.download(file, File(context.cacheDir, "cloud"))
            status = null
            result.fold(
                onSuccess = { local ->
                    status = "导入《${file.name}》…"
                    val imported = runCatching { repository.importFile(local, file.name) }
                    runCatching { local.delete() }
                    status = null
                    imported.fold(
                        onSuccess = { onImported(it.title) },
                        onFailure = { error = it.message ?: "导入失败" }
                    )
                },
                onFailure = { error = it.message ?: "下载失败" }
            )
        }
    }

    LaunchedEffect(type) { load(stack.last()) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Row {
                    if (stack.size > 1) {
                        IconButton(onClick = {
                            stack = stack.dropLast(1)
                            load(stack.last())
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "上级目录")
                        }
                    }
                    Text(
                        text = "${type.label}：${stack.last().name}",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 8.dp, top = 10.dp)
                    )
                }
                TextButton(onClick = onDismiss) { Text("关闭") }
            }

            status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            error?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (loading) Text("加载中…", style = MaterialTheme.typography.bodySmall)

            LazyColumn(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                items(files) { file ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (file.isDir) {
                                    stack = stack + file
                                    load(file)
                                } else if (file.isImportable()) {
                                    import(file)
                                }
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp)
                    ) {
                        Icon(
                            imageVector = if (file.isDir) Icons.Default.Folder else Icons.Default.MenuBook,
                            contentDescription = null,
                            tint = if (file.isImportable() || file.isDir) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            }
                        )
                        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(
                                text = file.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (!file.isDir) {
                                Text(
                                    text = if (file.isImportable()) formatSize(file.size) else "不支持的格式",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(size: Long): String = when {
    size <= 0 -> ""
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "${size / 1024} KB"
    else -> "${(size / 1024 / 1024)} MB"
}
