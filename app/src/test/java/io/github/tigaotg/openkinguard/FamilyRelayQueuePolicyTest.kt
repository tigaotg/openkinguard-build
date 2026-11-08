package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test

class FamilyRelayQueuePolicyTest {
    @Test fun queuedIsNotMistakenForDeviceReceipt() {
        for (persisted in listOf(true, false)) {
            val message = FamilyRelayQueuePolicy.queuedDescription(persisted)
            assertTrue(message.contains("尚无对方手机签名回执"))
            assertTrue(message.contains("绝不可视为已送达"))
            assertTrue(message.contains("5分钟"))
        }
    }

    @Test fun restartSafeAndMemoryOnlyQueuesHaveDifferentClaims() {
        assertTrue(FamilyRelayQueuePolicy.queuedDescription(true).contains("服务重启可恢复"))
        assertTrue(FamilyRelayQueuePolicy.queuedDescription(false).contains("运行内存"))
    }
}
