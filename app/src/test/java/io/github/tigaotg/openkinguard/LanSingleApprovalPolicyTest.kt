package io.github.tigaotg.openkinguard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LanSingleApprovalPolicyTest {
    @Test fun unsolicitedInvitationAlwaysRequiresHelpersExplicitConsent() {
        assertFalse(LanSingleApprovalPolicy.autoConfirm(PairingPhase.REVIEW_INVITE, false))
    }

    @Test fun preapprovedHelperOfferMayContinueOnlyMatchingSignedInvitation() {
        assertTrue(LanSingleApprovalPolicy.autoConfirm(PairingPhase.REVIEW_INVITE, true))
    }

    @Test fun safetyCodeReviewIsNeverAutomaticallySkipped() {
        assertFalse(LanSingleApprovalPolicy.autoConfirm(PairingPhase.REVIEW_RESPONSE, false))
        assertFalse(LanSingleApprovalPolicy.autoConfirm(PairingPhase.REVIEW_RESPONSE, true))
    }

    @Test fun finalSignedReceiptCanProceedAfterSubjectGrantsConsent() {
        assertTrue(LanSingleApprovalPolicy.autoConfirm(PairingPhase.REVIEW_GRANT, false))
        assertTrue(LanSingleApprovalPolicy.autoConfirm(PairingPhase.REVIEW_GRANT, true))
    }

    @Test fun waitingAndCompleteStatesMustNotEmitExtraFrames() {
        val inert = listOf(PairingPhase.WAIT_RESPONSE, PairingPhase.WAIT_GRANT,
            PairingPhase.WAIT_ACK, PairingPhase.ACK_TO_SHOW, PairingPhase.COMPLETE)
        for (phase in inert) {
            assertFalse(LanSingleApprovalPolicy.autoConfirm(phase, false))
            assertFalse(LanSingleApprovalPolicy.autoConfirm(phase, true))
        }
        assertFalse(LanSingleApprovalPolicy.autoConfirm(null, true))
    }
}
