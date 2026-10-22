package com.qingsongreader.ui.library

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qingsongreader.data.db.BookEntity
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.sync.BaiduPanClient
import kotlinx.coroutines.launch
import java.io.File

private val BOOK_EXTS = setOf("epub", "pdf", "mobi", "azw3", "txt", "md")

/**
 * Desktop UA for the QR-code login page: Baidu's passport widget renders its
 * desktop layout (QR tab by default) only for non-mobile user agents, while
 * the default Android WebView UA contains "Mobile" and gets the compact form.
 */
private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/120.0.0.0 Safari/537.36"

/**
 * The authorize page ships no viewport meta tag, so the WebView falls back to
 * a ~980px layout viewport — at that width Baidu's passport widget renders the
 * compact account/password form without its QR panel. Widening the viewport
 * makes the desktop layout (QR tab) render; the page then scales down to fit
 * and the user can pinch-zoom the QR code.
 */
private const val WIDE_VIEWPORT_JS = """
    (function () {
        var head = document.head;
        if (!head) return;
        var m = document.querySelector('meta[name="viewport"]');
        if (!m) {
            m = document.createElement('meta');
            m.name = 'viewport';
            head.appendChild(m);
        }
        m.setAttribute('content', 'width=1280');
    })();
"""

/**
 * Baidu Netdisk import: OAuth authorize (WebView extracts the oob code) →
 * list the book files inside the app's own cloud directory → tap to download
 * and import. The directory footnote discloses the only folder the app can
 * legally access, per the Baidu Netdisk Open Platform rules.
 */
@Composable
fun BaiduPanImportDialog(
    settings: ReaderSettings,
    onSettingsChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
    onImportFile: (File, String, (Result<BookEntity>) -> Unit) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // boot | config | auth | code | listing | list
    var mode by remember { mutableStateOf("boot") }
    var message by remember { mutableStateOf<String?>(null) }
    var files by remember { mutableStateOf<List<BaiduPanClient.CloudFile>?>(null) }
    var manualCode by remember { mutableStateOf("") }
    var downloading by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    // "page" = full-page login with a QR-code tab; "mobile" = touch form.
    var loginDisplay by remember { mutableStateOf("page") }
    // Kept locally: the persisted settings flow round-trips asynchronously,
    // so immediately-following API calls must not read it back.
    var activeToken by remember { mutableStateOf("") }

    fun saveTokens(pair: BaiduPanClient.TokenPair) {
        activeToken = pair.accessToken
        onSettingsChange {
            it.copy(
                baiduAccessToken = pair.accessToken,
                baiduRefreshToken = pair.refreshToken,
                baiduTokenExpiresMs = pair.expiresAtMs
            )
        }
    }

    fun loadList() {
        mode = "listing"
        scope.launch {
            BaiduPanClient.listBooks(activeToken).fold(
                onSuccess = {
                    files = it
                    mode = "list"
                },
                onFailure = {
                    message = it.message ?: "加载列表失败"
                    mode = "list"
                    files = emptyList()
                }
            )
        }
    }

    fun startWithToken() {
        scope.launch {
            val now = System.currentTimeMillis()
            when {
                !BaiduPanClient.configured() -> mode = "config"
                settings.baiduAccessToken.isBlank() -> mode = "auth"
                settings.baiduTokenExpiresMs > now -> {
                    activeToken = settings.baiduAccessToken
                    loadList()
                }
                else -> {
                    mode = "listing"
                    BaiduPanClient.refresh(settings.baiduRefreshToken).fold(
                        onSuccess = { pair ->
                            saveTokens(pair)
                            loadList()
                        },
                        onFailure = {
                            // Refresh failed: ask the user to authorize again.
                            onSettingsChange {
                                it.copy(baiduAccessToken = "", baiduRefreshToken = "", baiduTokenExpiresMs = 0L)
                            }
                            mode = "auth"
                        }
                    )
                }
            }
        }
    }

    fun handleCode(code: String) {
        mode = "listing"
        scope.launch {
            BaiduPanClient.exchangeCode(code.trim()).fold(
                onSuccess = { pair ->
                    saveTokens(pair)
                    loadList()
                },
                onFailure = {
                    message = "授权失败：${it.message}"
                    mode = "code"
                }
            )
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { startWithToken() }

    fun downloadAndImport(file: BaiduPanClient.CloudFile) {
        scope.launch {
            downloading = file.name
            progress = 0
            val dest = File(context.cacheDir, "baidu/" + file.name)
            BaiduPanClient.download(activeToken, file, dest) { p -> progress = p }.fold(
                onSuccess = {
                    downloading = null
                    onImportFile(it, file.name) { }
                    onDismiss()
                },
                onFailure = {
                    downloading = null
                    message = "下载失败：${it.message}"
                }
            )
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 28.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface
        ) {
            // No verticalScroll on this Column: a scrollable parent swallows the
            // WebView's touch gestures and makes the login form unreachable.
            // Each branch below scrolls its own content instead.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("从网盘导入", style = MaterialTheme.typography.titleLarge)
                Text(
                    "请把书籍存放在百度网盘 /apps（我的应用数据）/${BaiduPanClient.APP_NAME} 目录下。" +
                        "应用仅能调取该目录内的文件，不会读取网盘中的其他任何内容。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                when (mode) {
                    "boot" -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    "config" -> Column(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "网盘服务尚未接入。需开发者在百度网盘开放平台" +
                                "（pan.baidu.com/union）创建应用并配置接入凭证后即可使用，" +
                                "使用时只需登录百度账号授权，无需填写任何密钥。",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    "auth" -> {
                        Text(
                            "在下方页面登录百度账号并点击「授权」，完成后自动继续。" +
                                "推荐「扫码登录」：用百度 App 或百度网盘 App 的「扫一扫」扫描页面上的二维码，" +
                                "在手机上确认即可；也可切换到「账号密码登录」。无需填写任何密钥。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = loginDisplay == "page",
                                onClick = { loginDisplay = "page" },
                                label = { Text("扫码登录") }
                            )
                            FilterChip(
                                selected = loginDisplay == "mobile",
                                onClick = { loginDisplay = "mobile" },
                                label = { Text("账号密码登录") }
                            )
                        }
                        // key(loginDisplay) reloads the page in a fresh WebView
                        // whenever the login mode is switched.
                        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                            key(loginDisplay) {
                                AuthorizeWebView(
                                    display = loginDisplay,
                                    modifier = Modifier.fillMaxSize(),
                                    onCode = { code -> handleCode(code) }
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { mode = "code" }) { Text("手动输入授权码") }
                        }
                    }

                    "code" -> Column(
                        modifier = Modifier.fillMaxWidth().weight(1f)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            "若授权页面显示了授权码，请把它粘贴到这里：",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        OutlinedTextField(
                            value = manualCode,
                            onValueChange = { manualCode = it },
                            label = { Text("授权码") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = { mode = "auth" }) { Text("重新授权") }
                            TextButton(
                                onClick = { handleCode(manualCode) },
                                enabled = manualCode.isNotBlank()
                            ) { Text("确定") }
                        }
                    }

                    "listing" -> Box(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentAlignment = Alignment.Center
                    ) { CircularProgressIndicator() }

                    "list" -> {
                        files?.let { list ->
                            if (downloading != null) {
                                Text("正在下载《$downloading》 $progress%")
                                LinearProgressIndicator(
                                    progress = { progress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (list.isEmpty()) {
                                Text(
                                    if (message != null) "读取失败：$message"
                                    else "该目录还没有书籍文件。请把书上传到百度网盘" +
                                        " /apps（我的应用数据）/${BaiduPanClient.APP_NAME} 目录后点刷新。",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    items(list, key = { it.path }) { f ->
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            TextButton(onClick = { downloadAndImport(f) }) {
                                                Text(f.name, maxLines = 1)
                                            }
                                            Text(
                                                text = formatSize(f.size),
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(top = 14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = {
                                onSettingsChange {
                                    it.copy(baiduAccessToken = "", baiduRefreshToken = "", baiduTokenExpiresMs = 0L)
                                }
                                files = null
                                message = null
                                mode = "auth"
                            }) { Text("解除授权") }
                            TextButton(onClick = {
                                message = null
                                loadList()
                            }) { Text("刷新") }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun AuthorizeWebView(
    display: String,
    modifier: Modifier = Modifier,
    onCode: (String) -> Unit
) {
    var handled by remember { mutableStateOf(false) }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // The QR-code login is only served to desktop user agents;
                // the default Android WebView UA carries "Mobile" and gets
                // the compact account/password form instead.
                if (display == "page") settings.userAgentString = DESKTOP_UA
                // The full-page (QR-code) login is laid out for wide screens:
                // fit it into the viewport and keep pinch-zoom available so the
                // whole page — including the QR tab — stays reachable.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                isVerticalScrollBarEnabled = true
                isHorizontalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (display == "page") view.evaluateJavascript(WIDE_VIEWPORT_JS, null)
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        if (handled) return
                        if (display == "page") view.evaluateJavascript(WIDE_VIEWPORT_JS, null)
                        val pageUrl = url.orEmpty()
                        // Some flows put the code into the callback URL directly.
                        BaiduPanClient.extractCode(pageUrl)?.let { code ->
                            handled = true
                            onCode(code)
                            return
                        }
                        view.evaluateJavascript("document.body.innerText") { raw ->
                            if (handled) return@evaluateJavascript
                            val text = raw?.trim()?.removeSurrounding("\"")
                                ?.replace("\\n", "\n")
                                ?.replace("\\\"", "\"")
                                .orEmpty()
                            BaiduPanClient.extractCode(text)?.let { code ->
                                handled = true
                                onCode(code)
                            }
                        }
                    }
                }
                loadUrl(
                    if (display == "page") BaiduPanClient.qrLoginUrl()
                    else BaiduPanClient.authorizeUrl(display)
                )
            }
        }
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes / 1048576f)
    bytes >= 1 shl 10 -> "%d KB".format(bytes / 1024)
    else -> "$bytes B"
}
