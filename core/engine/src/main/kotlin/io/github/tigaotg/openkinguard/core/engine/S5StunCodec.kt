package io.github.tigaotg.openkinguard.core.engine

import java.net.InetAddress

/** RFC 8489 Binding codec for diagnostics and ICE candidate gathering only. */
object S5StunCodec {
    private val COOKIE = byteArrayOf(0x21, 0x12, 0xa4.toByte(), 0x42)

    data class MappedEndpoint(val address: String, val port: Int, val ipv6: Boolean)

    fun bindingRequest(transactionId: ByteArray): ByteArray {
        require(transactionId.size == 12)
        return byteArrayOf(0, 1, 0, 0) + COOKIE + transactionId
    }

    fun mappedAddress(bytes: ByteArray, expectedTransactionId: ByteArray): MappedEndpoint? {
        if (expectedTransactionId.size != 12 || bytes.size !in 20..2048 ||
            bytes[0] != 1.toByte() || bytes[1] != 1.toByte() ||
            !bytes.copyOfRange(4, 8).contentEquals(COOKIE) ||
            !bytes.copyOfRange(8, 20).contentEquals(expectedTransactionId)) return null
        val contentLength = u16(bytes, 2)
        if (contentLength > bytes.size - 20 || contentLength % 4 != 0) return null
        val end = 20 + contentLength
        var offset = 20
        while (offset + 4 <= end) {
            val type = u16(bytes, offset)
            val size = u16(bytes, offset + 2)
            val start = offset + 4
            if (size > end - start) return null
            if (type == 0x0020 && size >= 8) {
                val family = bytes[start + 1].toInt() and 255
                val addressLength = if (family == 1) 4 else if (family == 2) 16 else 0
                if (addressLength > 0 && size >= 4 + addressLength) {
                    val port = u16(bytes, start + 2) xor 0x2112
                    if (port !in 1..65535) return null
                    val decoded = ByteArray(addressLength) { index ->
                        val mask = if (index < 4) COOKIE[index] else expectedTransactionId[index - 4]
                        (bytes[start + 4 + index].toInt() xor mask.toInt()).toByte()
                    }
                    return MappedEndpoint(InetAddress.getByAddress(decoded).hostAddress.orEmpty(),
                        port, family == 2)
                }
            }
            // Attributes are padded to four-byte boundaries.
            offset = start + ((size + 3) and -4)
        }
        return null
    }

    private fun u16(data: ByteArray, index: Int): Int =
        ((data[index].toInt() and 255) shl 8) or (data[index + 1].toInt() and 255)
}
