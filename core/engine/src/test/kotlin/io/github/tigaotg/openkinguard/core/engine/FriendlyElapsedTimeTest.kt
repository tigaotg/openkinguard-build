package io.github.tigaotg.openkinguard.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class FriendlyElapsedTimeTest {
    private val now = 1_800_000_000_000L

    @Test fun showsTenThirtyOneAndFiveMinuteAges() {
        assertEquals("刚刚", FriendlyElapsedTime.since(now - 3_000L, now))
        assertEquals("10秒前", FriendlyElapsedTime.since(now - 14_000L, now))
        assertEquals("30秒前", FriendlyElapsedTime.since(now - 35_000L, now))
        assertEquals("1分钟前", FriendlyElapsedTime.since(now - 65_000L, now))
        assertEquals("5分钟前", FriendlyElapsedTime.since(now - 330_000L, now))
    }

    @Test fun handlesClockSkewWithoutClaimingOldFixIsFresh() {
        assertEquals("刚刚", FriendlyElapsedTime.since(now + 10_000L, now))
        assertEquals("时间未知", FriendlyElapsedTime.since(0L, now))
        assertEquals("2小时前", FriendlyElapsedTime.since(now - 7_200_000L, now))
    }
}
