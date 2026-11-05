package io.github.tigaotg.openkinguard

import kotlin.math.min

/**
 * Maps a touch on a stable fullscreen WebRTC FIT renderer to the actual displayed
 * remote pixels. Ignores touches on letterbox bars; does not stretch remote video.
 */
internal object RemoteAssistTouchGeometry {
    data class Point(val x: Int, val y: Int)

    fun map(
        x: Float, y: Float,
        containerWidth: Int, containerHeight: Int,
        videoWidth: Int, videoHeight: Int,
        clamp: Boolean = false
    ): Point? {
        if (containerWidth <= 0 || containerHeight <= 0 ||
            videoWidth <= 0 || videoHeight <= 0 ||
            !x.isFinite() || !y.isFinite()) return null

        val scale = min(containerWidth.toFloat() / videoWidth,
            containerHeight.toFloat() / videoHeight)
        val shownWidth = videoWidth * scale
        val shownHeight = videoHeight * scale
        val left = (containerWidth - shownWidth) / 2f
        val top = (containerHeight - shownHeight) / 2f
        if (!clamp && (x < left || x > left + shownWidth ||
                y < top || y > top + shownHeight)) return null

        return Point(
            (((x - left) / shownWidth) * 10000f).toInt().coerceIn(0, 10000),
            (((y - top) / shownHeight) * 10000f).toInt().coerceIn(0, 10000)
        )
    }
}
