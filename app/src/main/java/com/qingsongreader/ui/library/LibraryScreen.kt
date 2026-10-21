package com.qingsongreader.ui.library

import android.content.Intent
import android.net.Uri
import java.io.File
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
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingsongreader.BuildConfig
import coil.compose.AsyncImage
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.data.repo.ImportManager
import com.qingsongreader.sync.WebDavDocumentsProvider

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
    val importing by ImportManager.active.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<BookEntity?>(null) }
    var showAddPicker by remember { mutableStateOf(false) }

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

    // One SAF picker for both local files and cloud drives: it starts in the
    // local Downloads folder (EXTRA_INITIAL_URI resets any remembered
    // location, e.g. a previous WebDAV browse), and its drawer lists every
    // installed cloud provider (Baidu, Aliyun, ...) for one-tap switching.
    val pickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (uri != null) viewModel.import(uri, uri.lastPathSegment, ::reportImport)
    }

    val localRootUri = android.provider.DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents", "primary:Download"
    )

    fun safIntent(initial: Uri?): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
        addCategory(Intent.CATEGORY_OPENABLE)
        type = "*/*"
        if (initial != null) {
            putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
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
        Column(modifier = Modifier.fillMaxSize()) {
        // Background import progress (ImportManager survives navigation).
        importing.forEach { task ->
            Text(
                if (task.percent >= 0) "正在导入《${task.name}》 ${task.percent}%"
                else "正在导入《${task.name}》…",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)
            )
            LinearProgressIndicator(
                progress = { if (task.percent >= 0) task.percent / 100f else 1f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
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

    // "Add a book" entry: local / cloud-drive / WebDAV import routes.
    var cloudChoices by remember { mutableStateOf<List<Pair<String, Uri>>?>(null) }

    // Discovers every installed cloud drive that publishes a SAF documents
    // provider (Baidu Netdisk, Aliyun Drive, ...). Local storage / downloads /
    // media providers and our own WebDAV provider are excluded.
    fun probeCloudRoots(): List<Pair<String, Uri>> {
        val pm = context.packageManager
        val providers = runCatching {
            pm.queryIntentContentProviders(Intent("android.content.action.DOCUMENTS_PROVIDER"), 0)
        }.getOrNull() ?: return emptyList()
        val local = setOf(
            "com.android.externalstorage.documents",
            "com.android.providers.downloads.documents",
            "com.android.providers.media.documents",
            "com.android.mtp.documents"
        )
        val out = mutableListOf<Pair<String, Uri>>()
        for (info in providers) {
            val authority = info.providerInfo?.authority ?: continue
            if (authority in local || authority.startsWith("com.qingsongreader")) continue
            runCatching {
                context.contentResolver.query(
                    android.provider.DocumentsContract.buildRootsUri(authority),
                    null, null, null, null
                )?.use { c ->
                    val titleIdx = c.getColumnIndex(android.provider.DocumentsContract.Root.COLUMN_TITLE)
                    val docIdIdx = c.getColumnIndex(android.provider.DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                    while (c.moveToNext()) {
                        val docId = if (docIdIdx >= 0) c.getString(docIdIdx) else null
                        if (docId.isNullOrBlank()) continue
                        val title = if (titleIdx >= 0) (c.getString(titleIdx) ?: authority) else authority
                        out += title to android.provider.DocumentsContract.buildDocumentUri(authority, docId)
                    }
                }
            }
        }
        return out
    }

    if (showAddPicker) {
        AlertDialog(
            onDismissRequest = { showAddPicker = false },
            title = { Text("加入新书") },
            text = {
                Column {
                    TextButton(onClick = {
                        showAddPicker = false
                        pickerLauncher.launch(safIntent(localRootUri))
                    }) { Text("从本机或网盘导入") }
                    Text(
                        "打开文件管理器，点左上角菜单可切换百度网盘、阿里云盘等",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    )
                    TextButton(onClick = {
                        showAddPicker = false
                        coroutineScope.launch {
                            val roots = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probeCloudRoots() }
                            when {
                                roots.isEmpty() -> {
                                    pickerLauncher.launch(safIntent(localRootUri))
                                    snackbar.showSnackbar("未检测到已接入系统的网盘；请先安装并登录网盘 App（如百度网盘）再试")
                                }
                                roots.size == 1 -> pickerLauncher.launch(safIntent(roots[0].second))
                                else -> cloudChoices = roots
                            }
                        }
                    }) { Text("从网盘导入") }
                    Text(
                        "自动识别手机里已接入系统的网盘，选择后直接进入网盘目录挑选文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
                    )
                    TextButton(onClick = {
                        showAddPicker = false
                        pickerLauncher.launch(safIntent(WebDavDocumentsProvider.rootUri()))
                    }) { Text("从 WebDAV 导入") }
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddPicker = false }) { Text("取消") }
            }
        )
    }

    // Picking between several installed cloud drives.
    cloudChoices?.let { roots ->
        AlertDialog(
            onDismissRequest = { cloudChoices = null },
            title = { Text("选择网盘") },
            text = {
                Column {
                    roots.forEach { (title, uri) ->
                        TextButton(onClick = {
                            cloudChoices = null
                            pickerLauncher.launch(safIntent(uri))
                        }) { Text(title) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { cloudChoices = null }) { Text("取消") }
            }
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
