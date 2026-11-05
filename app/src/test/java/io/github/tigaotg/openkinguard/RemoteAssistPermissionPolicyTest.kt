package io.github.tigaotg.openkinguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteAssistPermissionPolicyTest {
    @Test fun previousPairingsRemainRequestableByDefault() {
        assertTrue(RemoteAssistPermissionPolicy.allowed(emptySet(), "relation-a"))
    }

    @Test fun revocationOnlyBlocksSelectedFamilyMember() {
        val denied = RemoteAssistPermissionPolicy.withPermission(
            emptySet(), "relation-a", false)
        assertFalse(RemoteAssistPermissionPolicy.allowed(denied, "relation-a"))
        assertTrue(RemoteAssistPermissionPolicy.allowed(denied, "relation-b"))
    }

    @Test fun restoringPermissionDoesNotPermitScreenCaptureAutomatically() {
        val denied = setOf("relation-a")
        val restored = RemoteAssistPermissionPolicy.withPermission(
            denied, "relation-a", true)
        assertTrue(RemoteAssistPermissionPolicy.allowed(restored, "relation-a"))
        // Actual capture still requires a new RemoteAssistConsent session and MediaProjection.
    }
}
