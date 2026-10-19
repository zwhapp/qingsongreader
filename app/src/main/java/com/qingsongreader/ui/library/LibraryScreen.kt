package com.qingsongreader.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingsongreader.BuildConfig
import coil.compose.AsyncImage
import com.qingsongreader.cloud.CloudManager
import com.qingsongreader.cloud.CloudType
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.ui.cloud.CloudBrowserSheet
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(
    settingsRepository: SettingsRepository,
    incomingUri: Uri?,
    onOpenBook: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStats: () -> Unit,
    viewModel: LibraryViewModel = viewModel()
) {
    val books by viewModel.books.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val cloudManager = remember(context) { CloudManager(context) }
    var cloudType by remember { mutableStateOf<CloudType?>(null) }
    var showCloudPicker by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }
    var showWebdavPicker by remember { mutableStateOf(false) }
    val webdavSettings by settingsRepository.settings.collectAsState(initial = null)

    fun reportImport(result: Result<BookEntity>) {
        coroutineScope.launch {
            snackbar.showSnackbar(
                result.fold(
                    onSuccess = { "已导入《${it.title}》" },
                    onFailure = { "导入失败：${it.message ?: "未知错误"}" }
                )
            )
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.import(uri, uri.lastPathSegment, ::reportImport)
    }

    LaunchedEffect(incomingUri) {
        if (incomingUri != null) {
            viewModel.import(incomingUri, incomingUri.lastPathSegment, ::reportImport)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("书架") },
                actions = {
                    IconButton(onClick = { showCloudPicker = true }) {
                        Icon(Icons.Default.Cloud, contentDescription = "网盘导入")
                    }
                    IconButton(onClick = onOpenStats) {
                        Icon(Icons.Default.BarChart, contentDescription = "统计")
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddPicker = true }) {
                Icon(Icons.Default.Add, contentDescription = "导入图书")
            }
        }
    ) { padding ->
        if (books.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.height(64.dp))
                    Text(
                        "书架空空，点右下角导入 EPUB / PDF / TXT / MD",
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        "轻松阅读 v${BuildConfig.VERSION_NAME}",
                        modifier = Modifier.padding(top = 4.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 120.dp),
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    BookCard(
                        book = book,
                        onClick = { onOpenBook(book.id) },
                        onLongClick = { pendingDelete = book }
                    )
                }
            }
        }
    }

    pendingDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除《${book.title}》？") },
            text = { Text("图书文件、高亮与笔记都会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(book)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }

    // "Add a book" entry: three import routes in one dialog.
    if (showAddPicker) {
        AlertDialog(
            onDismissRequest = { showAddPicker = false },
            title = { Text("加入新书") },
            text = {
                Column {
                    TextButton(onClick = {
                        showAddPicker = false
                        launcher.launch(
                            arrayOf(
                                "application/epub+zip",
                                "application/pdf",
                                "application/x-mobipocket-ebook",
                                "application/vnd.amazon.mobi8-ebook",
                                "text/plain",
                                "text/markdown",
                                "*/*"
                            )
                        )
                    }) { Text("从本地文件导入") }
                    TextButton(onClick = {
                        showAddPicker = false
                        showWebdavPicker = true
                    }) { Text("从我的 WebDAV 导入") }
                    TextButton(onClick = {
                        showAddPicker = false
                        showCloudPicker = true
                    }) { Text("从网盘导入") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddPicker = false }) { Text("取消") }
            }
        )
    }

    if (showWebdavPicker) {
        val dav = webdavSettings
        if (dav == null) {
            // settings not loaded yet — wait silently
        } else if (dav.webdavUrl.isBlank()) {
            AlertDialog(
                onDismissRequest = { showWebdavPicker = false },
                title = { Text("还没有配置 WebDAV") },
                text = { Text("请先在「设置 → 同步」里填写你自己的 WebDAV 地址与账号，再回来导入。") },
                confirmButton = {
                    TextButton(onClick = { showWebdavPicker = false }) { Text("知道了") }
                }
            )
        } else {
            WebDavImportSheet(
                url = dav.webdavUrl,
                user = dav.webdavUser,
                password = dav.webdavPassword,
                onImportFile = { file, name -> viewModel.importFile(file, name, ::reportImport) },
                onDismiss = { showWebdavPicker = false }
            )
        }
    }

    if (showCloudPicker) {
        val connected = listOf(CloudType.BAIDU, CloudType.ALIYUN)
            .filter { cloudManager.isConnected(it) }
        AlertDialog(
            onDismissRequest = { showCloudPicker = false },
            title = { Text("从网盘导入") },
            text = {
                Column {
                    if (connected.isEmpty()) {
                        Text("还没有连接网盘。请在「设置 → 网盘导入」里填入你自己申请的 AppKey/Secret 并授权。")
                    } else {
                        connected.forEach { type ->
                            TextButton(onClick = {
                                showCloudPicker = false
                                cloudType = type
                            }) {
                                Text("浏览 ${type.label}")
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCloudPicker = false }) { Text("关闭") }
            }
        )
    }

    cloudType?.let { type ->
        CloudBrowserSheet(
            type = type,
            manager = cloudManager,
            onImported = { title ->
                cloudType = null
                coroutineScope.launch { snackbar.showSnackbar("已导入《$title》") }
            },
            onDismiss = { cloudType = null }
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookCard(book: BookEntity, onClick: () -> Unit, onLongClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center
            ) {
                if (book.coverPath.isNotBlank() && File(book.coverPath).exists()) {
                    AsyncImage(
                        model = File(book.coverPath),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Default.MenuBook,
                        contentDescription = null,
                        modifier = Modifier.height(56.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = book.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 8.dp)
            )
            if (book.author.isNotBlank()) {
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(
                    progress = { book.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f).padding(top = 8.dp)
                )
            }
            Text(
                text = "${(book.progress * 100).toInt()}%",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(40.dp).padding(top = 4.dp)
            )
        }
    }
}
