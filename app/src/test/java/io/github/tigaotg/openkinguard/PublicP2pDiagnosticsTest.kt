package io.github.tigaotg.openkinguard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicP2pDiagnosticsTest {
    private val active = "UDP端口43026；本机候选2个；对端候选3个；已验签直连设备0个；握手超时"

    @Test fun noSignalingShowsEnableInstruction() {
        assertEquals("公共信令未开启",
            PublicP2pDiagnostics.explain(false, false, false, active).title)
    }

    @Test fun signalingAcknowledgedDoesNotCountAsP2p() {
        assertTrue(PublicP2pDiagnostics.explain(true, true, false, active)
            .title.contains("尚未开始直连"))
    }

    @Test fun missingPeerCandidatesIsNotHandshakeSuccess() {
        assertTrue(PublicP2pDiagnostics.explain(true, true, true,
            active.replace("对端候选3个", "对端候选0个"))
            .title.contains("尚未收到"))
    }

    @Test fun asymmetricCandidatesWarnOfNatOrFirewall() {
        assertTrue(PublicP2pDiagnostics.explain(true, true, true, active)
            .detail.contains("NAT"))
    }

    @Test fun onlySignedReplyCountsAsVerifiedP2p() {
        assertTrue(PublicP2pDiagnostics.explain(true, true, true, active,
            verifiedDirectCount = 1).title.contains("已验签直连"))
    }

    @Test fun verifiedDirectRemainsReportedWhileNostrReconnects() {
        assertTrue(PublicP2pDiagnostics.explain(true, false, true, active,
            verifiedDirectCount = 1).title.contains("已验签直连"))
    }

    @Test fun waitingForNetworkIsNotReportedAsUserNotStarted() {
        val status = "UDP监听已请求，等待系统默认网络；最近失败：NO_DEFAULT_NETWORK"
        assertTrue(PublicP2pDiagnostics.explain(true, true, false, status)
            .title.contains("等待系统网络恢复"))
    }

    @Test fun diagnosticTextCannotInventAVerifiedDirectRoute() {
        val misleading = active.replace("已验签直连设备0个", "已验签直连设备99个")
        assertTrue(PublicP2pDiagnostics.explain(true, true, true, misleading,
            verifiedDirectCount = 0).title.contains("已交换地址"))
    }

    @Test fun natNotesSurvivePrivacyRedaction() {
        val result = PublicP2pDiagnostics.safeStunSummary(
            "stun.qq.com→203.0.113.21:43210，约100毫秒；不同STUN目标映射不同，NAT可能不利于P2P穿透。")
        assertFalse(result.contains("203.0.113.21"))
        assertFalse(result.contains("43210"))
        assertTrue(result.contains("不同STUN目标映射不同"))
    }
}
