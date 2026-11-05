package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class RemoteAssistFrameLayoutTest {
    @Test fun portraitPhoneShowsBothSidesWithoutCropping() {
        val size = requireNotNull(RemoteAssistFrameLayout.fit(360f, 640f, 720, 1600))
        assertEquals(288f, size.width, 0.01f)
        assertEquals(640f, size.height, 0.01f)
    }

    @Test fun landscapeRemoteFitsControllerPortraitArea() {
        val size = requireNotNull(RemoteAssistFrameLayout.fit(360f, 640f, 1280, 720))
        assertEquals(360f, size.width, 0.01f)
        assertEquals(202.5f, size.height, 0.01f)
    }

    @Test fun mismatchedAspectNeverOverflowsOrStretches() {
        val sizes = listOf(720 to 1600, 1280 to 720, 1440 to 3200,
            1080 to 2160, 1080 to 1920, 1600 to 900)
        for ((w,h) in sizes) {
            val size = requireNotNull(RemoteAssistFrameLayout.fit(393f, 775f, w, h))
            assertTrue(size.width <= 393.0001f)
            assertTrue(size.height <= 775.0001f)
            assertEquals(w.toFloat() / h, size.width / size.height, 0.0001f)
        }
    }

    @Test fun invalidDimensionsDoNotShowCropFrame() {
        assertNull(RemoteAssistFrameLayout.fit(0f, 700f, 720, 1600))
        assertNull(RemoteAssistFrameLayout.fit(360f, 700f, 0, 1600))
    }
}
