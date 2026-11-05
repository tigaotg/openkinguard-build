package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class FamilyConnectionPresentationTest {
    @Test fun connectingRequiresSavedEnabledAndListeningNode() {
        val waiting = FamilyConnectionPresentation.banner(true, true, true, false, false)
        assertTrue(waiting.attempting)
        assertTrue(waiting.title.contains("正在尝试连接"))
        assertFalse(FamilyConnectionPresentation.banner(true, false, true, false, false).attempting)
        assertFalse(FamilyConnectionPresentation.banner(true, true, false, false, false).attempting)
    }

    @Test fun revocationIsNotDescribedAsNetworkRetry() {
        val revoked = FamilyConnectionPresentation.banner(true, true, true, false, true)
        assertFalse(revoked.attempting)
        assertTrue(revoked.instruction.contains("重新授权"))
    }

    @Test fun alreadyConnectedStillRequiresMessageAcknowledgement() {
        val result = FamilyConnectionPresentation.banner(true, true, true, true, false)
        assertFalse(result.attempting)
        assertTrue(result.instruction.contains("送达确认"))
    }

    @Test fun remotePermissionAndAuthorizationAreDifferent() {
        assertTrue(LocationActionPresentation.message("REMOTE_DENIED").contains("定位授权"))
        assertTrue(LocationActionPresentation.message("REMOTE_OS_PERMISSION").contains("手机设置"))
        assertFalse(LocationActionPresentation.message("NETWORK_TIMEOUT").contains("拒绝"))
    }
}
