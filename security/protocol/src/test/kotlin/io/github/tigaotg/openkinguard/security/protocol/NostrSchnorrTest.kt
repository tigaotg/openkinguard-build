package io.github.tigaotg.openkinguard.security.protocol

import org.junit.Assert.*
import org.junit.Test

class NostrSchnorrTest {
    private fun hex(value: String): ByteArray = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** The BIP-340 project's official vector #0, including its exact deterministic signature. */
    @Test fun officialVectorZero() {
        val secret = hex("0000000000000000000000000000000000000000000000000000000000000003")
        val public = hex("F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9")
        val message = ByteArray(32)
        val signature = hex("E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215" +
            "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0")
        assertArrayEquals(public, NostrSchnorr.publicKey(secret))
        assertArrayEquals(signature, NostrSchnorr.sign(message, secret, ByteArray(32)))
        assertTrue(NostrSchnorr.verify(message, public, signature))
        message[0] = 1
        assertFalse(NostrSchnorr.verify(message, public, signature))
    }

    @Test fun randomlyGeneratedKeysAndTampering() {
        val secret = NostrSchnorr.createSecret()
        val public = NostrSchnorr.publicKey(secret)
        val msg = ByteArray(32) { it.toByte() }
        val signed = NostrSchnorr.sign(msg, secret)
        assertTrue(NostrSchnorr.verify(msg, public, signed))
        signed[20] = (signed[20].toInt() xor 1).toByte()
        assertFalse(NostrSchnorr.verify(msg, public, signed))
        assertFalse(NostrSchnorr.validSecret(ByteArray(32)))
    }
}
