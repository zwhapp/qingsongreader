package com.qingsongreader.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.readerPrefs: DataStore<Preferences> by preferencesDataStore(name = "reader_settings")

enum class ThemeMode { LIGHT, DARK, SEPIA }
enum class ViewMode { PAGED, SCROLLED }
enum class TextAlignSetting { JUSTIFY, LEFT }

data class ReaderSettings(
    val fontSizeSp: Int = 18,
    val fontFamily: String = "system",
    val lineHeight: Float = 1.6f,
    val paragraphSpacing: Float = 0.8f,
    val pageMarginDp: Int = 16,
    val themeMode: ThemeMode = ThemeMode.LIGHT,
    val viewMode: ViewMode = ViewMode.PAGED,
    val textAlign: TextAlignSetting = TextAlignSetting.JUSTIFY,
    val keepScreenOn: Boolean = false,
    val fullscreen: Boolean = false,
    val volumeKeysTurnPages: Boolean = true,
    val ttsRate: Float = 1.0f,
    val ttsPitch: Float = 1.0f,
    /**
     * Look the selected word up automatically instead of via the toolbar button.
     * On by default: selecting text in EPUB/TXT pops the dictionary right away.
     */
    val autoLookup: Boolean = true,
    /** E-ink / Kindle mode: pure black-on-white, no colour, reduced motion. */
    val einkMode: Boolean = false,
    /** Use a serif typeface (better on e-ink / paper-like screens). */
    val useSerif: Boolean = false,
    /** Automatically check GitHub for updates on launch. */
    val autoCheckUpdate: Boolean = false,
    val webdavUrl: String = "",
    val webdavUser: String = "",
    val webdavPassword: String = ""
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val FONT_SIZE = intPreferencesKey("font_size_sp")
        val FONT_FAMILY = stringPreferencesKey("font_family")
        val LINE_HEIGHT = floatPreferencesKey("line_height")
        val PARAGRAPH_SPACING = floatPreferencesKey("paragraph_spacing")
        val PAGE_MARGIN = intPreferencesKey("page_margin_dp")
        val THEME = stringPreferencesKey("theme_mode")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val TEXT_ALIGN = stringPreferencesKey("text_align")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val FULLSCREEN = booleanPreferencesKey("fullscreen")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        val TTS_RATE = floatPreferencesKey("tts_rate")
        val TTS_PITCH = floatPreferencesKey("tts_pitch")
        val AUTO_LOOKUP = booleanPreferencesKey("auto_lookup")
        val EINK_MODE = booleanPreferencesKey("eink_mode")
        val USE_SERIF = booleanPreferencesKey("use_serif")
        val AUTO_CHECK_UPDATE = booleanPreferencesKey("auto_check_update")
        val WEBDAV_URL = stringPreferencesKey("webdav_url")
        val WEBDAV_USER = stringPreferencesKey("webdav_user")
        val WEBDAV_PASSWORD = stringPreferencesKey("webdav_password")
    }

    val settings: Flow<ReaderSettings> = context.readerPrefs.data.map { fromPrefs(it) }

    suspend fun update(mutate: (ReaderSettings) -> ReaderSettings) {
        context.readerPrefs.edit { p ->
            val next = mutate(fromPrefs(p))
            p[Keys.FONT_SIZE] = next.fontSizeSp
            p[Keys.FONT_FAMILY] = next.fontFamily
            p[Keys.LINE_HEIGHT] = next.lineHeight
            p[Keys.PARAGRAPH_SPACING] = next.paragraphSpacing
            p[Keys.PAGE_MARGIN] = next.pageMarginDp
            p[Keys.THEME] = next.themeMode.name
            p[Keys.VIEW_MODE] = next.viewMode.name
            p[Keys.TEXT_ALIGN] = next.textAlign.name
            p[Keys.KEEP_SCREEN_ON] = next.keepScreenOn
            p[Keys.FULLSCREEN] = next.fullscreen
            p[Keys.VOLUME_KEYS] = next.volumeKeysTurnPages
            p[Keys.TTS_RATE] = next.ttsRate
            p[Keys.TTS_PITCH] = next.ttsPitch
            p[Keys.AUTO_LOOKUP] = next.autoLookup
            p[Keys.EINK_MODE] = next.einkMode
            p[Keys.USE_SERIF] = next.useSerif
            p[Keys.AUTO_CHECK_UPDATE] = next.autoCheckUpdate
            p[Keys.WEBDAV_URL] = next.webdavUrl
            p[Keys.WEBDAV_USER] = next.webdavUser
            p[Keys.WEBDAV_PASSWORD] = next.webdavPassword
        }
    }

    /** Convenience setters used by the settings screen. */
    suspend fun setFontSize(sp: Int) = update { it.copy(fontSizeSp = sp) }
    suspend fun setFontFamily(family: String) = update { it.copy(fontFamily = family) }
    suspend fun setLineHeight(value: Float) = update { it.copy(lineHeight = value) }
    suspend fun setParagraphSpacing(value: Float) = update { it.copy(paragraphSpacing = value) }
    suspend fun setPageMargin(dp: Int) = update { it.copy(pageMarginDp = dp) }
    suspend fun setTheme(mode: ThemeMode) = update { it.copy(themeMode = mode) }
    suspend fun setViewMode(mode: ViewMode) = update { it.copy(viewMode = mode) }
    suspend fun setTextAlign(align: TextAlignSetting) = update { it.copy(textAlign = align) }
    suspend fun setKeepScreenOn(value: Boolean) = update { it.copy(keepScreenOn = value) }
    suspend fun setFullscreen(value: Boolean) = update { it.copy(fullscreen = value) }
    suspend fun setTts(rate: Float, pitch: Float) = update { it.copy(ttsRate = rate, ttsPitch = pitch) }
    suspend fun setWebDav(url: String, user: String, password: String) =
        update { it.copy(webdavUrl = url, webdavUser = user, webdavPassword = password) }

    private fun fromPrefs(p: Preferences): ReaderSettings = ReaderSettings(
        fontSizeSp = p[Keys.FONT_SIZE] ?: 18,
        fontFamily = p[Keys.FONT_FAMILY] ?: "system",
        lineHeight = p[Keys.LINE_HEIGHT] ?: 1.6f,
        paragraphSpacing = p[Keys.PARAGRAPH_SPACING] ?: 0.8f,
        pageMarginDp = p[Keys.PAGE_MARGIN] ?: 16,
        themeMode = runCatching { ThemeMode.valueOf(p[Keys.THEME] ?: "LIGHT") }.getOrDefault(ThemeMode.LIGHT),
        viewMode = runCatching { ViewMode.valueOf(p[Keys.VIEW_MODE] ?: "PAGED") }.getOrDefault(ViewMode.PAGED),
        textAlign = runCatching { TextAlignSetting.valueOf(p[Keys.TEXT_ALIGN] ?: "JUSTIFY") }.getOrDefault(TextAlignSetting.JUSTIFY),
        keepScreenOn = p[Keys.KEEP_SCREEN_ON] ?: false,
        fullscreen = p[Keys.FULLSCREEN] ?: false,
        volumeKeysTurnPages = p[Keys.VOLUME_KEYS] ?: true,
        ttsRate = p[Keys.TTS_RATE] ?: 1.0f,
        ttsPitch = p[Keys.TTS_PITCH] ?: 1.0f,
        autoLookup = p[Keys.AUTO_LOOKUP] ?: true,
        einkMode = p[Keys.EINK_MODE] ?: false,
        useSerif = p[Keys.USE_SERIF] ?: false,
        autoCheckUpdate = p[Keys.AUTO_CHECK_UPDATE] ?: false,
        webdavUrl = p[Keys.WEBDAV_URL] ?: "",
        webdavUser = p[Keys.WEBDAV_USER] ?: "",
        webdavPassword = p[Keys.WEBDAV_PASSWORD] ?: ""
    )
}
