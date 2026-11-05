package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssistPrivateRelayPolicyTest {
    @Test fun signedRtcAndManualGesturesMayUseOnlyThePrivateRelay() {
        listOf(
            RemoteAssistStreamWire.RTC,
            RemoteAssistStreamWire.DIAGNOSTIC,
            RemoteAssistStreamWire.TAP,
            RemoteAssistStreamWire.SWIPE,
            RemoteAssistStreamWire.BACK,
            RemoteAssistStreamWire.HOME,
            RemoteAssistStreamWire.RECENTS,
        ).forEach { assertTrue(RemoteAssistStreamWire.canUsePrivateRelay(it)) }
    }

    @Test fun mediaFramesOrResponsesAreNeverOutboundRelayRequests() {
        listOf(RemoteAssistStreamWire.ACK, "VIDEO_FRAME", "JPEG_FRAGMENT",
            "RAW_H264", "", "UNKNOWN").forEach {
            assertFalse(RemoteAssistStreamWire.canUsePrivateRelay(it))
        }
    }
}
