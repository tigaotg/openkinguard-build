package io.github.tigaotg.openkinguard.core.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class S5StunCodecTest {
    private val transaction = ByteArray(12) { (it + 1).toByte() }

    @Test fun requestIsRfc8489TwentyByteBindingRequest() {
        val frame = S5StunCodec.bindingRequest(transaction)
        assertEquals(20, frame.size)
        assertContentEquals(transaction, frame.copyOfRange(8, 20))
        assertEquals(0, frame[0].toInt())
        assertEquals(1, frame[1].toInt())
    }

    @Test fun decodesXorMappedIpv4AddressAndPort() {
        // XOR-MAPPED-ADDRESS for 1.2.3.4:54321, cookie 0x2112a442.
        val frame = S5StunCodec.bindingRequest(transaction).also {
            it[0] = 1
            it[1] = 1
        } + byteArrayOf(
            0x00, 0x20, 0x00, 0x08, 0x00, 0x01,
            (54321 xor 0x2112).ushr(8).toByte(), (54321 xor 0x2112).toByte(),
            0x20, 0x10, 0xa7.toByte(), 0x46,
        )
        frame[3] = 12
        val result = S5StunCodec.mappedAddress(frame, transaction)
        assertEquals("1.2.3.4", result?.address)
        assertEquals(54321, result?.port)
        assertEquals(false, result?.ipv6)
    }

    @Test fun rejectsOtherTransactionOrTruncation() {
        val frame = S5StunCodec.bindingRequest(transaction).also { it[0] = 1; it[1] = 1 }
        assertNull(S5StunCodec.mappedAddress(frame, ByteArray(12)))
        assertNull(S5StunCodec.mappedAddress(frame + byteArrayOf(0, 0), transaction))
    }
}
