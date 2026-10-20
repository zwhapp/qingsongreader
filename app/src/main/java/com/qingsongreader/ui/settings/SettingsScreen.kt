package com.qingsongreader.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.data.prefs.ThemeMode
import com.qingsongreader.data.prefs.ViewMode
import com.qingsongreader.features.update.Updater
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val settings by viewModel.settings.collectAsState()
    val dictCount by viewModel.dictCount.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    LaunchedEffect(Unit) {
        if (settings.autoCheckUpdate && updateState is Updater.UpdateState.Idle) {
            viewModel.checkForUpdate()
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val dictLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val result = viewModel.importDictionary(uri)
                snackbar.showSnackbar(
                    result.fold({ "已导入 $it 条词条" }, { "导入失败：${it.message}" })
                )
            }
        }
    }

    var davUrl by remember(settings.webdavUrl) { mutableStateOf(settings.webdavUrl) }
    var builtInReady by remember { mutableStateOf(viewModel.builtInImported()) }
    var reimporting by remember { mutableStateOf(false) }
    var davUser by remember(settings.webdavUser) { mutableStateOf(settings.webdavUser) }
    var davPass by remember(settings.webdavPassword) { mutableStateOf(settings.webdavPassword) }

    // Debounce-save WebDAV credentials so "从 WebDAV 导入" (SAF DocumentsProvider)
    // sees them without requiring an explicit 上传/下载 tap.
    LaunchedEffect(davUrl, davUser, davPass) {
        if (davUrl != settings.webdavUrl || davUser != settings.webdavUser || davPass != settings.webdavPassword) {
            kotlinx.coroutines.delay(600)
            viewModel.update {
                it.copy(webdavUrl = davUrl.trim(), webdavUser = davUser.trim(), webdavPassword = davPass.trim())
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("阅读排版", style = MaterialTheme.typography.titleMedium)
            Text("字号：${settings.fontSizeSp} sp")
            Slider(
                value = settings.fontSizeSp.toFloat(),
                onValueChange = { value -> viewModel.update { it.copy(fontSizeSp = value.toInt()) } },
                valueRange = 12f..36f
            )
            Text("行距：${String.format("%.1f", settings.lineHeight)}")
            Slider(
                value = settings.lineHeight,
                onValueChange = { value -> viewModel.update { it.copy(lineHeight = value) } },
                valueRange = 1.0f..3.0f
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.LIGHT to "明亮", ThemeMode.SEPIA to "纸色", ThemeMode.DARK to "夜间").forEach { (mode, label) ->
                    FilterChip(
                        selected = settings.themeMode == mode,
                        onClick = { viewModel.update { it.copy(themeMode = mode) } },
                        label = { Text(label) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.einkMode,
                    onClick = { viewModel.update { it.copy(einkMode = !it.einkMode) } },
                    label = { Text("墨水屏 / Kindle") }
                )
                FilterChip(
                    selected = settings.useSerif,
                    onClick = { viewModel.update { it.copy(useSerif = !it.useSerif) } },
                    label = { Text("衬线字体") }
                )
            }
            Text(
                "墨水屏模式：纯黑底白字、去掉彩色与动画，适配 Kindle Fire、文石、掌阅等电纸书；" +
                    "配合「常亮」关闭与翻页键翻页，残影更少、更省电。",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.viewMode == ViewMode.PAGED,
                    onClick = { viewModel.update { it.copy(viewMode = ViewMode.PAGED) } },
                    label = { Text("分页") }
                )
                FilterChip(
                    selected = settings.viewMode == ViewMode.SCROLLED,
                    onClick = { viewModel.update { it.copy(viewMode = ViewMode.SCROLLED) } },
                    label = { Text("滚动") }
                )
                FilterChip(
                    selected = settings.keepScreenOn,
                    onClick = { viewModel.update { it.copy(keepScreenOn = !it.keepScreenOn) } },
                    label = { Text("常亮") }
                )
            }

            Text("词典", style = MaterialTheme.typography.titleMedium)
            Text(
                "内置：汉字表（Unicode® Unihan，Unicode 许可）+ 汉英/英汉词典（CC-CEDICT，CC BY-SA 4.0，" +
                    "约 11.4 万中文词条、4 万英文词条），首次启动自动导入，完全离线。",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "内置词库：${if (builtInReady) "已导入" else "正在导入…"}",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = {
                    scope.launch {
                        reimporting = true
                        val result = viewModel.reimportBuiltIn()
                        reimporting = false
                        builtInReady = true
                        snackbar.showSnackbar(
                            result.fold(
                                onSuccess = { "内置词库已导入 $it 条" },
                                onFailure = { "导入失败：${it.message}" }
                            )
                        )
                    }
                },
                enabled = !reimporting
            ) { Text(if (reimporting) "导入中…" else "重新导入内置词库") }
            Text(
                "已收录 ${dictCount} 条词条（离线词库 + 查词缓存）。查询顺序：离线词库 → 免费在线词典（英文）。",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { dictLauncher.launch(arrayOf("application/json", "text/csv", "text/plain", "*/*")) }) {
                    Text("导入离线词库")
                }
                Button(onClick = {
                    scope.launch {
                        viewModel.clearDictionary()
                        snackbar.showSnackbar("词典已清空")
                    }
                }) { Text("清空词典") }
            }
            Text(
                "支持格式：JSON 数组 [{\"word\":\"apple\",\"phonetic\":\"ˈæpl\",\"definition\":\"苹果\"}]，" +
                    "或 CSV / TSV（word,phonetic,definition；两列时第二列作释义）。",
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.autoLookup,
                    onClick = { viewModel.update { it.copy(autoLookup = !it.autoLookup) } },
                    label = { Text("选中即查词") }
                )
            }

            Text("同步（自建 WebDAV，无容量限制）", style = MaterialTheme.typography.titleMedium)
            Text(
                "上传：阅读数据（进度/批注/书签）+ 全部书籍文件。下载：恢复到本机，书籍文件会自动重新拉取。",
                style = MaterialTheme.typography.bodySmall
            )
            OutlinedTextField(
                value = davUrl,
                onValueChange = { davUrl = it },
                label = { Text("WebDAV 目录地址") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = davUser,
                onValueChange = { davUser = it },
                label = { Text("用户名") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = davPass,
                onValueChange = { davPass = it },
                label = { Text("密码") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        viewModel.update { it.copy(webdavUrl = davUrl, webdavUser = davUser, webdavPassword = davPass) }
                        val result = viewModel.syncUpload(davUrl, davUser, davPass)
                        snackbar.showSnackbar(result.fold(
                            { "已上传：阅读数据 + $it 个书籍文件" },
                            { "上传失败：${it.message}" }
                        ))
                    }
                }) { Text("上传") }
                Button(onClick = {
                    scope.launch {
                        viewModel.update { it.copy(webdavUrl = davUrl, webdavUser = davUser, webdavPassword = davPass) }
                        val result = viewModel.syncDownload(davUrl, davUser, davPass)
                        snackbar.showSnackbar(result.fold(
                            { r ->
                                if (r.skipped > 0) "已恢复 ${r.imported} 本书（${r.skipped} 个文件缺失已跳过）"
                                else "已恢复 ${r.imported} 本书（含书籍文件）"
                            },
                            { "下载失败：${it.message}" }
                        ))
                    }
                }) { Text("下载") }
                Button(onClick = {
                    scope.launch {
                        val json = viewModel.exportText()
                        clipboard.setText(AnnotatedString(json))
                        snackbar.showSnackbar("已复制备份 JSON")
                    }
                }) { Text("导出到剪贴板") }
            }

            // ------------------------------------------------ 关于与更新
            Text("关于与更新", style = MaterialTheme.typography.titleMedium)
            Text(
                "轻松阅读 (qingsongreader) · 当前版本 v${viewModel.currentVersion}",
                style = MaterialTheme.typography.bodyMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.checkForUpdate() }) {
                    Text("检查更新")
                }
                FilterChip(
                    selected = settings.autoCheckUpdate,
                    onClick = {
                        viewModel.update { it.copy(autoCheckUpdate = !it.autoCheckUpdate) }
                        if (!settings.autoCheckUpdate) viewModel.checkForUpdate()
                    },
                    label = { Text("启动时自动检查") }
                )
            }
            when (val us = updateState) {
                is Updater.UpdateState.Checking ->
                    Text("正在检查更新…", style = MaterialTheme.typography.bodySmall)
                is Updater.UpdateState.UpToDate ->
                    Text("已是最新版本 (v${us.version})", style = MaterialTheme.typography.bodySmall)
                is Updater.UpdateState.Error ->
                    Text("更新检查出错：${us.message}", style = MaterialTheme.typography.bodySmall)
                is Updater.UpdateState.Downloading ->
                    LinearProgressIndicator(
                        progress = { (us.progress / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                else -> Unit
            }
            // GitHub 加速镜像：直连失败时用 URL 前缀代理访问 Release。
            var mirrorText by remember(settings.githubMirror) { mutableStateOf(settings.githubMirror) }
            LaunchedEffect(mirrorText) {
                if (mirrorText.trim() != settings.githubMirror) {
                    kotlinx.coroutines.delay(600)
                    viewModel.update { it.copy(githubMirror = mirrorText.trim()) }
                }
            }
            OutlinedTextField(
                value = mirrorText,
                onValueChange = { mirrorText = it },
                label = { Text("加速镜像（可选）") },
                placeholder = { Text("留空 = 直连 GitHub") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "" to "直连",
                    "https://gh-proxy.com/" to "gh-proxy.com",
                    "https://ghfast.top/" to "ghfast.top",
                    "https://ghproxy.net/" to "ghproxy.net"
                ).forEach { (prefix, label) ->
                    FilterChip(
                        selected = settings.githubMirror == prefix,
                        onClick = {
                            mirrorText = prefix
                            viewModel.update { it.copy(githubMirror = prefix) }
                        },
                        label = { Text(label) }
                    )
                }
            }
            Text(
                "更新源：GitHub Releases（zwhapp/qingsongreader）。直连失败时选择或填写加速镜像" +
                    "（格式如 https://gh-proxy.com/），新版本会下载 APK 并调用系统安装器。",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                "说明：云同步使用你自己的 WebDAV，本应用不代理任何第三方付费服务。",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp)
            )
        }
    }



    if (updateState is Updater.UpdateState.Available) {
        val info = (updateState as Updater.UpdateState.Available).info
        AlertDialog(
            onDismissRequest = { viewModel.dismissUpdate() },
            title = { Text("发现新版本 v${info.version}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (info.name.isNotBlank()) {
                        Text(info.name, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        info.body.ifBlank { "暂无更新说明。" },
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.downloadAndInstall(info) }) { Text("下载并安装") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUpdate() }) { Text("稍后") }
            }
        )
    }
}
