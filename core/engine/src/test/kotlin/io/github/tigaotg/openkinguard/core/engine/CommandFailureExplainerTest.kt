package io.github.tigaotg.openkinguard.core.engine

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.crypto.AEADBadTagException

class CommandFailureExplainerTest {
    @Test fun `signed denial is a verified refusal`() {
        val failure = CommandFailureExplainer.explain(RemoteLocationDenied(
            RemoteLocationDenial.NOT_ALLOWED))
        assertEquals("REMOTE_DENIED", failure.code)
        assertTrue(failure.message.contains("明确拒绝"))
    }

    @Test fun `every signed denial has a distinct explanation`() {
        val codes = RemoteLocationDenial.entries.map {
            CommandFailureExplainer.explain(RemoteLocationDenied(it)).code
        }
        assertEquals(4, codes.toSet().size)
    }

    @Test fun `timeout does not mean authorization denied`() {
        val failure = CommandFailureExplainer.explain(SocketTimeoutException("Read timed out"))
        assertEquals("NETWORK_TIMEOUT", failure.code)
        assertTrue(failure.message.contains("不能据此判断"))
        assertFalse(failure.message.contains("明确拒绝"))
    }

    @Test fun `timeout hidden under wrapper is still identified`() {
        val failure = CommandFailureExplainer.explain(IllegalStateException(
            "unknown", SocketTimeoutException("Read timed out")))
        assertEquals("NETWORK_TIMEOUT", failure.code)
    }

    @Test fun `connection refused and broken connection differ`() {
        assertEquals("CONNECT_FAILED", CommandFailureExplainer.explain(ConnectException()).code)
        assertEquals("CONNECTION_INTERRUPTED", CommandFailureExplainer.explain(EOFException()).code)
        assertEquals("CONNECTION_INTERRUPTED", CommandFailureExplainer.explain(SocketException()).code)
    }

    @Test fun `missing network route is not a refusal`() {
        assertEquals("NETWORK_UNREACHABLE", CommandFailureExplainer.explain(
            NoRouteToHostException()).code)
        assertEquals("NETWORK_UNREACHABLE", CommandFailureExplainer.explain(
            UnknownHostException()).code)
    }

    @Test fun `authenticated encryption tampering has specific failure`() {
        assertEquals("CRYPTO_FAILED", CommandFailureExplainer.explain(AEADBadTagException()).code)
    }

    @Test fun `local system permission is not remote denial`() {
        assertEquals("LOCAL_PERMISSION", CommandFailureExplainer.explain(SecurityException()).code)
    }

    @Test fun `invalid reply is rejected with bounded detail`() {
        val failure = CommandFailureExplainer.explain(IllegalArgumentException("失败\n" + "X".repeat(400)))
        assertEquals("INVALID_COMMAND", failure.code)
        assertFalse(failure.message.contains("\n"))
        assertTrue(failure.message.length < 210)
    }

    @Test fun `unverified relation state explains local reason`() {
        val failure = CommandFailureExplainer.explain(IllegalStateException("对方暂未完成局域网认证"))
        assertEquals("NOT_READY", failure.code)
        assertTrue(failure.message.contains("认证"))
    }
}
