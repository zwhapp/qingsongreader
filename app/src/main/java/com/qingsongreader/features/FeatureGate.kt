package com.qingsongreader.features

/**
 * Feature gate.
 *
 * OpenReader ships with every capability enabled: there is no account, no
 * subscription check and no entitlement round-trip. Every switch below maps to
 * a capability that paid tiers gate elsewhere (custom themes and fonts,
 * read-aloud, translation, cloud sync, parallel reading, statistics, ...).
 * They all resolve to true unconditionally.
 *
 * Cloud sync and AI features need credentials that only the user can supply
 * (their own WebDAV server, their own model/translation API key). Nothing here
 * talks to a third party's paid service, and no quota is enforced by the app.
 */
object FeatureGate {

    /** Master switch kept for readability at call sites. */
    const val ALL_FEATURES_UNLOCKED: Boolean = true

    val isPro: Boolean get() = ALL_FEATURES_UNLOCKED

    fun isEnabled(feature: PremiumFeature): Boolean = ALL_FEATURES_UNLOCKED

    /** No quota bookkeeping in this build: limits are meaningless. */
    val unlimitedTranslation: Boolean get() = ALL_FEATURES_UNLOCKED
    val unlimitedReadAloud: Boolean get() = ALL_FEATURES_UNLOCKED
    val unlimitedCloudStorage: Boolean get() = ALL_FEATURES_UNLOCKED

    fun translationQuotaCharsPerDay(): Int = Int.MAX_VALUE
    fun readAloudQuotaMinutes(): Int = Int.MAX_VALUE
    fun cloudStorageQuotaBytes(): Long = Long.MAX_VALUE
}

enum class PremiumFeature(val label: String) {
    CUSTOM_THEMES("自定义主题"),
    CUSTOM_FONTS("自定义字体"),
    ADVANCED_LAYOUT("高级排版"),
    READ_ALOUD("AI 朗读"),
    AI_TRANSLATION("AI 翻译"),
    AI_ASSISTANT("AI 助手"),
    CLOUD_SYNC("云同步"),
    PARALLEL_READ("平行阅读"),
    READING_STATS("阅读统计"),
    DICTIONARY("词典/百科"),
    TTS("系统语音朗读"),
    EXPORT("批注导出"),
    OPDS("OPDS 书库"),
    CLOUD_IMPORT("网盘导入")
}
