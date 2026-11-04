package io.github.tigaotg.openkinguard

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator

class S2RelayPinTest {
    @Test fun privateIpv6WithExactPinIsAccepted() {
        val pin = "a".repeat(64)
        assertEquals("wss://[2409:8a70::1234]:9443/relay#spki=$pin",
            S2RelayClient.normalize("wss://[2409:8a70::1234]:9443#spki=$pin"))
        assertEquals("wss://example.org/relay", S2RelayClient.normalize("wss://example.org"))
    }

    @Test fun rejectUnknownFragmentsAndPlaintext() {
        listOf("ws://127.0.0.1:9443/relay#spki=${"a".repeat(64)}",
            "wss://127.0.0.1:9443/relay#spki=123",
            "wss://127.0.0.1:9443/relay#danger=1",
            "wss://127.0.0.1:9443/relay#spki=${"A".repeat(64)}").forEach { address ->
            assertThrows(IllegalArgumentException::class.java) {
                S2RelayClient.normalize(address)
            }
        }
    }

    @Test fun pinMatchesOnlyExactPublicKey() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(256)
        val one = generator.generateKeyPair().public
        val other = generator.generateKeyPair().public
        val pin = S2RelayTls.pinFromPublicKey(one)
        assertEquals(64, pin.length)
        assertTrue(S2RelayTls.verifyPin(one, pin))
        assertFalse(S2RelayTls.verifyPin(other, pin))
        assertFalse(S2RelayTls.verifyPin(one, pin.uppercase()))
        assertFalse(S2RelayTls.verifyPin(one, "0".repeat(64)))
    }
}
