package com.qingsongreader.ui.reader

import android.view.KeyEvent

/**
 * Routes hardware key events from the host Activity to the reader that is
 * currently on screen.
 *
 * Why a dispatcher instead of overriding onKeyDown in MainActivity directly:
 * the reader lives in a Compose Navigation graph, so only the visible
 * ReaderScreen knows how to turn a page (WebView paged/scrolled, PDF, ...).
 * ReaderScreen registers a callback while it is composed and unregisters on
 * dispose; everywhere else (library, settings, dialogs) keys keep their
 * default behaviour.
 *
 * Mapping (Chinese reading convention, matches Bigme B751C physical buttons):
 *   upper key  -> previous page (backwards)
 *   lower key  -> next page     (forwards)
 *
 * Covered key codes:
 *   KEYCODE_VOLUME_UP / DOWN   -> gated by [volumeEnabled]
 *   KEYCODE_PAGE_UP / DOWN     -> gated by [pageKeysEnabled]
 *   KEYCODE_DPAD_UP / LEFT     -> previous page
 *   KEYCODE_DPAD_DOWN / RIGHT  -> next page
 *
 * Long-press repeats are swallowed (consumed, no action) so holding a button
 * never flips a burst of pages on a slow e-ink panel.
 */
object ReaderKeyDispatcher {

    @Volatile
    private var onTurnPage: ((Int) -> Unit)? = null

    @Volatile
    var volumeEnabled: Boolean = true
        private set

    @Volatile
    var pageKeysEnabled: Boolean = true
        private set

    /** Called by ReaderScreen while it is the visible destination. */
    fun register(turnPage: (Int) -> Unit, volume: Boolean, pageKeys: Boolean) {
        volumeEnabled = volume
        pageKeysEnabled = pageKeys
        onTurnPage = turnPage
    }

    fun unregister() {
        onTurnPage = null
    }

    /**
     * @return true when the event was consumed by the reader and must not be
     * passed on (e.g. to the system volume panel).
     */
    fun dispatch(event: KeyEvent): Boolean {
        val turn = onTurnPage ?: return false
        val code = event.keyCode
        val delta = when (code) {
            KeyEvent.KEYCODE_VOLUME_UP -> if (volumeEnabled) -1 else return false
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (volumeEnabled) 1 else return false
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT -> if (pageKeysEnabled) -1 else return false
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (pageKeysEnabled) 1 else return false
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            turn(delta)
        }
        // Consume both DOWN and UP (and repeats) so nothing leaks to the system.
        return true
    }
}
