package com.qingsongreader

import android.os.SystemClock
import android.view.MotionEvent
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.qingsongreader.ui.reader.PdfPageView
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Multi-touch verification for the PDF viewer zoom gestures. adb's `input`
 * command cannot synthesize two-finger gestures, so real MotionEvents are
 * injected through UiAutomation instead.
 */
@RunWith(AndroidJUnit4::class)
class PdfZoomGestureTest {

    @get:Rule
    val compose = createComposeRule()

    private val pdfFile: File by lazy {
        File("/sdcard/Download/pagetest.pdf")
            .takeIf { it.exists() } ?: File("/sdcard/Download/xjtu2027.pdf")
    }

    @Test
    fun pinchOut_zooms_in() {
        assertTrue("test PDF missing on device: ${pdfFile.absolutePath}", pdfFile.exists())
        var lastScale = 1f
        compose.setContent {
            PdfPageView(
                file = pdfFile,
                page = 0,
                onTurnPage = {},
                onScaleChanged = { lastScale = it },
            )
        }
        compose.waitForIdle()
        Thread.sleep(1500) // let the first bitmap render

        val downTime = SystemClock.uptimeMillis()
        fun props(vararg ids: Int) = ids.map { id ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }.toTypedArray()

        fun coords(vararg pts: Pair<Int, Int>) = pts.map { (x, y) ->
            MotionEvent.PointerCoords().apply {
                this.x = x.toFloat()
                this.y = y.toFloat()
                pressure = 1f
                size = 1f
            }
        }.toTypedArray()

        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        fun inject(event: MotionEvent) {
            check(automation.injectInputEvent(event, false)) { "inject failed: $event" }
        }

        // 1st finger down
        inject(
            MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN,
                1, props(0), coords(400 to 1000), 0, 0f, 1f, 0, 0, 0, 0
            )
        )
        // 2nd finger down (pinch start: 280 px apart)
        inject(
            MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props(0, 1), coords(400 to 1000, 680 to 1000), 0, 0f, 1f, 0, 0, 0, 0
            )
        )
        // spread to ~620 px apart (zoom ≈ 2.2x)
        for (i in 1..8) {
            Thread.sleep(30)
            inject(
                MotionEvent.obtain(
                    downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE,
                    2, props(0, 1),
                    coords((400 - 13 * i) to (1000 - 25 * i), (680 + 13 * i) to (1000 + 25 * i)),
                    0, 0f, 1f, 0, 0, 0, 0
                )
            )
        }
        // lift the 2nd finger, then the 1st
        inject(
            MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(),
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                2, props(0, 1), coords(296 to 800, 784 to 1200), 0, 0f, 1f, 0, 0, 0, 0
            )
        )
        inject(
            MotionEvent.obtain(
                downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP,
                1, props(0), coords(296 to 800), 0, 0f, 1f, 0, 0, 0, 0
            )
        )

        compose.waitForIdle()
        Thread.sleep(600) // let the state settle
        assertTrue("pinch-out did not zoom, last scale = $lastScale", lastScale > 1.5f)
    }
}
