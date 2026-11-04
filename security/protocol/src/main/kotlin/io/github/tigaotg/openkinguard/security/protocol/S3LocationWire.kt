package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.MessageEnvelope
import java.util.Base64
import java.util.UUID

/** Signed, strictly bounded one-shot position request; no unconditional tracking grant. */
object S3LocationWire {
    const val REQUEST = "OKG_S3_LOCATION_REQUEST_V1"
    const val RESULT = "OKG_S3_LOCATION_RESULT_V1"
    const val DENIED = "OKG_S3_LOCATION_DENIED_V1"
    val kinds = setOf(REQUEST, RESULT, DENIED)
    val reasons = setOf("NOT_ALLOWED", "NO_PERMISSION", "FOREGROUND_ONLY", "UNAVAILABLE")

    data class Fix(
        val latitudeE7: Int,
        val longitudeE7: Int,
        val accuracyCm: Int,
        val capturedAtMillis: Long,
        val provider: String,
    ) {
        init {
            require(latitudeE7 in -900_000_000..900_000_000)
            require(longitudeE7 in -1_800_000_000..1_800_000_000)
            require(accuracyCm in 1..500_000)
            require(capturedAtMillis in 1..253_402_300_799_000L)
            require(provider == "G" || provider == "N")
        }
        val latitude get() = latitudeE7 / 10_000_000.0
        val longitude get() = longitudeE7 / 10_000_000.0
        val accuracyMeters get() = accuracyCm / 100.0
    }

    data class Body(
        val relationId: String,
        val eventId: String,
        val respondingTo: String? = null,
        val requestPublicKey: String? = null,
        val encrypted: EncryptedFix? = null,
        val denial: String? = null,
    )

    data class EncryptedFix(val ephemeralPublicKey: String, val nonce: String,
                            val ciphertext: String)

    fun request(relationId: String, eventId: String, ephemeralPublicKey: String): ByteArray =
        "Q|${uuid(relationId)}|${uuid(eventId)}|${encodedKey(ephemeralPublicKey)}"
            .toByteArray(Charsets.US_ASCII)

    fun result(relationId: String, eventId: String, requestId: String,
               encrypted: EncryptedFix): ByteArray =
        ("L|${uuid(relationId)}|${uuid(eventId)}|${uuid(requestId)}|" +
            "${encodedKey(encrypted.ephemeralPublicKey)}|" +
            "${encodedBytes(encrypted.nonce, 12, 12)}|" +
            encodedBytes(encrypted.ciphertext, 17, 160))
            .toByteArray(Charsets.US_ASCII)

    fun denied(relationId: String, eventId: String, requestId: String,
               reason: String): ByteArray {
        require(reason in reasons)
        return "D|${uuid(relationId)}|${uuid(eventId)}|${uuid(requestId)}|$reason"
            .toByteArray(Charsets.US_ASCII)
    }

    fun body(message: MessageEnvelope): Body {
        require(message.payloadKind in kinds) { "Unsupported position command" }
        val bytes = message.payload
        require(bytes.size in 4..480 && bytes.all { it.toInt() in 32..126 }) {
            "Invalid position payload encoding"
        }
        val fields = bytes.toString(Charsets.US_ASCII).split('|')
        val request = message.payloadKind == REQUEST
        val result = message.payloadKind == RESULT
        val expectedSize = when {
            request -> 4
            result -> 7
            else -> 5
        }
        val expectedPrefix = if (request) "Q" else if (result) "L" else "D"
        require(fields.size == expectedSize && fields[0] == expectedPrefix) {
            "Invalid position command shape"
        }
        val relationId = uuid(fields[1])
        val eventId = uuid(fields[2])
        require(message.eventId.value == eventId) { "Position event ID mismatch" }
        val acknowledged = if (request) null else uuid(fields[3])
        val requestKey = if (request) encodedKey(fields[3]) else null
        val encrypted = if (result) EncryptedFix(encodedKey(fields[4]),
            encodedBytes(fields[5], 12, 12), encodedBytes(fields[6], 17, 160)) else null
        val denial = if (!request && !result) fields[4].also { require(it in reasons) } else null
        return Body(relationId, eventId, acknowledged, requestKey, encrypted, denial)
    }

    /** A stale or implausibly future-dated result must never be shown as a live fix. */
    fun fresh(fix: Fix, nowMillis: Long): Boolean {
        val age = nowMillis - fix.capturedAtMillis
        return age in -30_000L..60_000L
    }

    private fun encodedKey(value: String): String {
        encodedBytes(value, 70, 200)
        require(runCatching { PairingQrProtocol.publicKey(Base64.getUrlDecoder().decode(value)) }
            .isSuccess) { "Invalid ephemeral P256 public key" }
        return value
    }

    private fun encodedBytes(value: String, min: Int, max: Int): String {
        require(value.length in 8..300)
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size in min..max && Base64.getUrlEncoder().withoutPadding()
            .encodeToString(bytes) == value) { "Invalid encrypted position field" }
        return value
    }

    private fun uuid(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value) {
            "Invalid position relation or event ID"
        }
        return value
    }
}
