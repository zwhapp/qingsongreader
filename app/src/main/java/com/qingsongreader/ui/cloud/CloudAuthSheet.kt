package com.qingsongreader.ui.cloud

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Provider sign-in page. The redirect is intercepted to grab `code`; for
 * providers using the out-of-band flow (Baidu "oob") the code is shown on the
 * page and can be pasted into the field below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudAuthSheet(
    authorizeUrl: String,
    onCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var manualCode by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                "登录并授权后，若页面显示授权码请粘贴到下方输入框；若自动跳转则会自动捕获。",
                style = MaterialTheme.typography.bodySmall
            )
            AndroidView(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                val uri = request?.url
                                val code = uri?.getQueryParameter("code")
                                if (!code.isNullOrBlank()) {
                                    onCode(code)
                                    return true
                                }
                                return false
                            }
                        }
                        loadUrl(authorizeUrl)
                    }
                },
                onRelease = { it.destroy() }
            )
            OutlinedTextField(
                value = manualCode,
                onValueChange = { manualCode = it },
                label = { Text("授权码（code）") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            TextButton(onClick = { if (manualCode.isNotBlank()) onCode(manualCode.trim()) }) {
                Text("用授权码连接")
            }
        }
    }
}
