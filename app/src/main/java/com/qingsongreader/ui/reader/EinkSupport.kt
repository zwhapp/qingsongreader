package com.qingsongreader.ui.reader

import android.os.Build

/**
 * Best-effort detection of e-ink / e-paper devices, used only to show a
 * tailored hint in Settings. Never gates functionality: every e-ink option
 * stays available on every device.
 */
object EinkSupport {

    private val VENDOR_HINTS = listOf(
        "bigme", "boox", "onyx", "ireader", "kindle", "amazon",
        "hisense", "meebook", "moan", "hyread", "kobo", "pocketbook",
    )

    fun isLikelyEinkDevice(): Boolean {
        val fp = "${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL} ${Build.DEVICE}"
            .lowercase()
        return VENDOR_HINTS.any { fp.contains(it) }
    }

    /** Short label like "Bigme B751C" when recognised, null otherwise. */
    fun deviceLabel(): String? {
        if (!isLikelyEinkDevice()) return null
        val model = Build.MODEL.trim()
        val manufacturer = Build.MANUFACTURER.trim()
        return if (model.startsWith(manufacturer, ignoreCase = true)) model
        else "$manufacturer $model".trim()
    }
}
