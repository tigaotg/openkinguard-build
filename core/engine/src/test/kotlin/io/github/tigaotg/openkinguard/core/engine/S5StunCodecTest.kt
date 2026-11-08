package io.github.tigaotg.openkinguard.core.engine

import java.net.InetAddress

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

    @Test fun decodesXorMappedIpv6AddressAndPort() {
        val address = InetAddress.getByName("2001:4860:4860::8888").address
        val cookie = byteArrayOf(0x21, 0x12, 0xa4.toByte(), 0x42)
        val port = 54321
        val encodedAddress = ByteArray(16) { index ->
            val mask = if (index < 4) cookie[index] else transaction[index - 4]
            (address[index].toInt() xor mask.toInt()).toByte()
        }
        val frame = S5StunCodec.bindingRequest(transaction).also {
            it[0] = 1
            it[1] = 1
            // Attribute body is 20 bytes plus its 4-byte attribute header.
            it[3] = 24
        } + byteArrayOf(
            0x00, 0x20, 0x00, 0x14, 0x00, 0x02,
            (port xor 0x2112).ushr(8).toByte(), (port xor 0x2112).toByte()
        ) + encodedAddress

        val result = S5StunCodec.mappedAddress(frame, transaction)
        assertEquals(port, result?.port)
        assertEquals(true, result?.ipv6)
        assertContentEquals(address, InetAddress.getByName(requireNotNull(result).address).address)
    }

    @Test fun rejectsOtherTransactionOrTruncation() {
        val frame = S5StunCodec.bindingRequest(transaction).also { it[0] = 1; it[1] = 1 }
        assertNull(S5StunCodec.mappedAddress(frame, ByteArray(12)))
        assertNull(S5StunCodec.mappedAddress(frame + byteArrayOf(0, 0), transaction))
    }
}
