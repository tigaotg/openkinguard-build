package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class S3FixQualityTest {
    @Test fun `coarse cached location cannot terminate new GPS acquisition`() {
        assertFalse(S3FixQuality.canUseCacheImmediately(2_000L, 250f))
        assertFalse(S3FixQuality.canUseCacheImmediately(20_000L, 12f))
        assertTrue(S3FixQuality.canUseCacheImmediately(2_000L, 18f))
    }

    @Test fun `early completion requires a reasonably accurate measured fix`() {
        assertFalse(S3FixQuality.goodEnoughToFinish(150f))
        assertFalse(S3FixQuality.goodEnoughToFinish(0f))
        assertFalse(S3FixQuality.goodEnoughToFinish(Float.NaN))
        assertTrue(S3FixQuality.goodEnoughToFinish(32f))
    }

    @Test fun `rough network result must warn instead of pretending satellite accuracy`() {
        assertTrue(S3FixQuality.description("N", 450f).contains("精度较低"))
        assertTrue(S3FixQuality.description("N", 25f).contains("网络定位"))
        assertTrue(S3FixQuality.description("G", 18f).contains("GPS"))
    }
}
