package io.github.tigaotg.openkinguard

import kotlin.math.min

/**
 * Pure aspect-FIT calculation. Entire remote frame is visible; never uses CROP
 * or aspect-FILL. The bottom navigation strip is excluded by the calling UI.
 */
internal object RemoteAssistFrameLayout {
    data class Size(val width: Float, val height: Float)

    fun fit(viewWidth: Float, viewHeight: Float,
            frameWidth: Int, frameHeight: Int): Size? {
        if (viewWidth <= 0f || viewHeight <= 0f ||
            frameWidth <= 0 || frameHeight <= 0 ||
            !viewWidth.isFinite() || !viewHeight.isFinite()) return null
        val scale = min(viewWidth / frameWidth, viewHeight / frameHeight)
        return Size(frameWidth * scale, frameHeight * scale)
    }
}
