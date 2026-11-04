package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test

class DeviceActionPolicyTest {
    @Test fun `new discovery advertisement is not hidden by old local alias`() {
        assertEquals("新设备名", DeviceActionPolicy.discoveryLabel(
            "新设备名", "之前验签名", "旧备注", "service"))
        assertEquals("之前验签名", DeviceActionPolicy.discoveryLabel(
            "", "之前验签名", "旧备注", "service"))
        assertEquals("service", DeviceActionPolicy.discoveryLabel("", "", "", "service"))
    }

    @Test fun `targeted checkin never reaches other authorized helpers`() {
        assertTrue(DeviceActionPolicy.canDeliverCheckin("relation-A", "relation-A"))
        assertFalse(DeviceActionPolicy.canDeliverCheckin("relation-A", "relation-B"))
        assertTrue(DeviceActionPolicy.canDeliverCheckin("", "relation-A"))
        assertTrue(DeviceActionPolicy.canDeliverCheckin("", "relation-B"))
    }
}
