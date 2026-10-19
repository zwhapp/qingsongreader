package com.qingsongreader.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.navigation.compose.rememberNavController
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.data.prefs.ThemeMode
import com.qingsongreader.ui.nav.AppNav
import com.qingsongreader.ui.theme.OpenReaderTheme

class MainActivity : ComponentActivity() {

    /**
     * Book URI handed over by other apps ("open with" from WeChat, a file
     * manager, ...). Kept in state so onNewIntent updates it while the
     * activity is already running (launchMode=singleTask).
     */
    private val incomingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingUri.value = intent?.data
        val settingsRepository = SettingsRepository(this)

        setContent {
            val settings: ReaderSettings by settingsRepository.settings.collectAsState(
                initial = ReaderSettings()
            )
            val incoming by incomingUri
            val darkTheme = settings.themeMode == ThemeMode.DARK
            OpenReaderTheme(
                darkTheme = darkTheme,
                einkMode = settings.einkMode,
                serif = settings.useSerif
            ) {
                AppNav(
                    navController = rememberNavController(),
                    settingsRepository = settingsRepository,
                    incomingUri = incoming
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.data != null) incomingUri.value = intent.data
    }
}
