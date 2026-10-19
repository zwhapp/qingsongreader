package com.qingsongreader.ui.reader

import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

private const val MAX_SCALE = 4f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val BASE_RENDER_WIDTH = 1080
private const val MAX_RENDER_WIDTH = 3240

/**
 * Renders a single PDF page with the platform renderer (no extra dependency).
 *
 * Supports pinch-to-zoom, pan while zoomed, and double-tap to toggle zoom.
 * The bitmap is re-rendered at a higher resolution (debounced) while zoomed so
 * the page stays sharp. Single-finger swipe and bottom-zone taps turn pages
 * and stay disabled while zoomed in, so gestures never fight each other.
 */
@Composable
fun PdfPageView(
    file: File,
    page: Int,
    onTurnPage: (Int) -> Unit,
    onScaleChanged: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var dragX by remember { mutableFloatStateOf(0f) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(scale) { onScaleChanged(scale) }

    // Render resolution follows the zoom level (debounced) so the bitmap stays
    // sharp when zoomed in without re-rendering on every gesture frame.
    var renderScale by remember { mutableFloatStateOf(1f) }
    LaunchedEffect(scale) {
        if (scale > 1.02f) {
            delay(180)
            renderScale = scale
        } else {
            renderScale = 1f
        }
    }

    val bitmap by produceState<Bitmap?>(
        initialValue = null,
        key1 = file,
        key2 = page,
        key3 = renderScale,
    ) {
        android.util.Log.d("OR_DEBUG", "render start page=$page scale=$renderScale")
        value = withContext(Dispatchers.IO) { renderPage(file, page, renderScale) }
        android.util.Log.d("OR_DEBUG", "render done page=$page scale=$renderScale null=${value == null}")
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it },
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY,
                    )
                    // Unified gesture: two fingers zoom + pan, one finger pans
                    // the zoomed page or (at 1x) swipes to turn pages.
                    // NOTE: separate detectTransformGestures / detectDragGestures
                    // do NOT work together — whichever consumes first starves the
                    // other (single-finger input is eaten by the drag detector,
                    // so pan-while-zoomed silently does nothing). Hence one
                    // detector that decides by pointer count.
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val startScale = scale
                            val startOffsetX = offsetX
                            val startOffsetY = offsetY
                            var multiPointer = false
                            var zoomAccum = 1f
                            var panAccum = Offset.Zero
                            var pastSlop = false
                            val slop = viewConfiguration.touchSlop

                            while (true) {
                                val event = awaitPointerEvent()
                                val pointers = event.changes.filter { it.pressed }
                                if (pointers.size >= 2) multiPointer = true

                                if (multiPointer && pointers.size >= 2) {
                                    // Zoom = ratio of the two-pointer distance
                                    // (current vs. previous frame); pan = average
                                    // pointer movement. Uses only stable APIs —
                                    // calculateZoom/calculatePan are internal.
                                    val cur = (pointers[0].position - pointers[1].position).getDistance()
                                    val prev = (pointers[0].previousPosition - pointers[1].previousPosition).getDistance()
                                    val zoomChange = if (prev > 1f) cur / prev else 1f
                                    var panX = 0f
                                    var panY = 0f
                                    pointers.forEach {
                                        panX += it.position.x - it.previousPosition.x
                                        panY += it.position.y - it.previousPosition.y
                                    }
                                    val panChange = Offset(panX / pointers.size, panY / pointers.size)
                                    if (zoomChange != 1f || panChange != Offset.Zero) {
                                        zoomAccum *= zoomChange
                                        panAccum += panChange
                                        event.changes.forEach { it.consume() }
                                        val newScale = (startScale * zoomAccum).coerceIn(1f, MAX_SCALE)
                                        scale = newScale
                                        if (newScale > 1f) {
                                            val maxX = viewport.width * (newScale - 1f) / 2f
                                            val maxY = viewport.height * (newScale - 1f) / 2f
                                            offsetX = (startOffsetX + panAccum.x).coerceIn(-maxX, maxX)
                                            offsetY = (startOffsetY + panAccum.y).coerceIn(-maxY, maxY)
                                        } else {
                                            offsetX = 0f
                                            offsetY = 0f
                                        }
                                    }
                                } else {
                                    val panChange = pointers.firstOrNull()?.let {
                                        it.position - it.previousPosition
                                    } ?: Offset.Zero
                                    if (panChange != Offset.Zero) {
                                        panAccum += panChange
                                        if (!pastSlop && panAccum.getDistance() > slop) pastSlop = true
                                        if (pastSlop) {
                                            event.changes.forEach { it.consume() }
                                            if (scale > 1f) {
                                                val maxX = viewport.width * (scale - 1f) / 2f
                                                val maxY = viewport.height * (scale - 1f) / 2f
                                                offsetX = (startOffsetX + panAccum.x).coerceIn(-maxX, maxX)
                                                offsetY = (startOffsetY + panAccum.y).coerceIn(-maxY, maxY)
                                            } else {
                                                dragX = panAccum.x
                                            }
                                        }
                                    }
                                }
                                if (event.changes.none { it.pressed }) break
                            }

                            if (pastSlop && !multiPointer && scale <= 1f) {
                                if (dragX < -80) onTurnPage(1)
                                else if (dragX > 80) onTurnPage(-1)
                                dragX = 0f
                            }
                        }
                    }
                    // Tap the bottom-right / bottom-left area to turn pages;
                    // double-tap anywhere toggles zoom.
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { offset ->
                                if (scale <= 1f && offset.y >= size.height * 0.6f) {
                                    onTurnPage(if (offset.x >= size.width / 2f) 1 else -1)
                                }
                            },
                            onDoubleTap = {
                                if (scale > 1f) {
                                    scale = 1f
                                    offsetX = 0f
                                    offsetY = 0f
                                } else {
                                    scale = DOUBLE_TAP_SCALE
                                }
                            },
                        )
                    },
            )
        } else {
            CircularProgressIndicator()
        }
    }
}

private fun renderPage(file: File, page: Int, scale: Float): Bitmap? = runCatching {
    val targetWidth = (BASE_RENDER_WIDTH * scale).roundToInt().coerceIn(1, MAX_RENDER_WIDTH)
    ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { renderer ->
            val index = page.coerceIn(0, max(0, renderer.pageCount - 1))
            renderer.openPage(index).use { pdfPage ->
                val ratio = targetWidth.toFloat() / pdfPage.width.toFloat()
                val width = (pdfPage.width * ratio).toInt().coerceAtLeast(1)
                val height = (pdfPage.height * ratio).toInt().coerceAtLeast(1)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(android.graphics.Color.WHITE)
                pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            }
        }
    }
}.getOrNull()
