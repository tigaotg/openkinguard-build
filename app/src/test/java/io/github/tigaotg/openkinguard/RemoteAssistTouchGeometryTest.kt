package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class RemoteAssistTouchGeometryTest {
    @Test fun portraitFullFrameAndCorners() {
        assertEquals(RemoteAssistTouchGeometry.Point(5000, 5000),
            RemoteAssistTouchGeometry.map(540f, 1200f, 1080, 2400, 720, 1600))
        assertEquals(RemoteAssistTouchGeometry.Point(0, 0),
            RemoteAssistTouchGeometry.map(0f, 0f, 1080, 2400, 720, 1600))
        assertEquals(RemoteAssistTouchGeometry.Point(10000, 10000),
            RemoteAssistTouchGeometry.map(1080f, 2400f, 1080, 2400, 720, 1600))
    }

    @Test fun landscapeVideoInPortraitContainerLetterboxes() {
        // 16:9 landscape picture inside tall phone screen.
        assertEquals(RemoteAssistTouchGeometry.Point(5000, 5000),
            RemoteAssistTouchGeometry.map(540f, 1200f, 1080, 2400, 1280, 720))
        assertNull(RemoteAssistTouchGeometry.map(540f, 100f, 1080, 2400, 1280, 720))
        assertEquals(RemoteAssistTouchGeometry.Point(0, 5000),
            RemoteAssistTouchGeometry.map(0f, 1200f, 1080, 2400, 1280, 720))
    }

    @Test fun swipeCanClampAtVideoBounds() {
        assertNull(RemoteAssistTouchGeometry.map(20f, 30f,
            1080, 2400, 1280, 720))
        val point = RemoteAssistTouchGeometry.map(20f, 30f,
            1080, 2400, 1280, 720, clamp = true)
        assertEquals(0, point?.y)
    }

    @Test fun invalidBoundsIgnored() {
        assertNull(RemoteAssistTouchGeometry.map(20f, 10f, 0, 100, 720, 1600))
        assertNull(RemoteAssistTouchGeometry.map(Float.NaN, 0f, 100, 100, 720, 1600))
    }
}
