package com.qingsongreader.ui

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.compose.rememberNavController
import com.qingsongreader.data.prefs.ReaderSettings
import com.qingsongreader.data.prefs.SettingsRepository
import com.qingsongreader.data.prefs.ThemeMode
import com.qingsongreader.ui.nav.AppNav
import com.qingsongreader.ui.theme.OpenReaderTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val incomingUri: Uri? = intent?.data
        val settingsRepository = SettingsRepository(this)

        setContent {
            val settings: ReaderSettings by settingsRepository.settings.collectAsState(
                initial = ReaderSettings()
            )
            val darkTheme = settings.themeMode == ThemeMode.DARK
            OpenReaderTheme(
                darkTheme = darkTheme,
                einkMode = settings.einkMode,
                serif = settings.useSerif
            ) {
                AppNav(
                    navController = rememberNavController(),
                    settingsRepository = settingsRepository,
                    incomingUri = incomingUri
                )
            }
        }
    }
}
