package io.github.tigaotg.openkinguard.security.protocol

import io.github.tigaotg.openkinguard.core.model.MessageEnvelope
import java.util.Base64
import java.util.UUID

/** Signed case commands. All SOS/check-in/control bodies are S4 encrypted on the wire. */
object S1CaseWire {
    const val SOS = "OKG_S1_TEST_SOS_V1"
    const val RECEIPT = "OKG_S1_TEST_RECEIPT_V1"
    const val CLAIM = "OKG_S1_TEST_CLAIM_V1"
    const val ACCEPT = "OKG_S1_TEST_ACCEPT_V1"
    const val REJECT = "OKG_S1_TEST_REJECT_V1"
    const val CLOSE = "OKG_S1_TEST_CLOSE_V1"
    const val CLOSED = "OKG_S1_TEST_CLOSED_V1"
    const val CHECKIN = "OKG_S1_CHECKIN_V1"
    const val CHECKIN_RECEIPT = "OKG_S1_CHECKIN_RECEIPT_V1"
    const val PROFILE = "OKG_S1_PROFILE_V1"
    const val PROFILE_REPLY = "OKG_S1_PROFILE_REPLY_V1"

    val requestKinds = setOf(SOS, CLAIM, CLOSE, CHECKIN, PROFILE)
    val responseKinds = setOf(RECEIPT, ACCEPT, REJECT, CLOSED, CHECKIN_RECEIPT, PROFILE_REPLY)
    /** No plaintext compatibility path exists for business commands or receipts. */
    val encryptedKinds = (requestKinds + responseKinds) - setOf(PROFILE, PROFILE_REPLY)

    data class Body(
        val relationId: String,
        val eventId: String,
        val respondingTo: String? = null,
        val profileNickname: String? = null,
        val eventCreatedAtMillis: Long? = null,
        val senderNickname: String? = null,
        val encryptionCard: String? = null,
    )

    fun request(
        relationId: String,
        eventId: String,
        eventCreatedAtMillis: Long? = null,
        senderNickname: String? = null,
        encryptionCard: String? = null,
    ): ByteArray {
        val time = eventCreatedAtMillis?.let {
            require(it in 1..253_402_300_799_000L) { "Invalid source event time" }
            "|$it"
        }.orEmpty()
        val nick = senderNickname?.let { "|N:" + encodeNickname(it) }.orEmpty()
        val card = encryptionCard?.let { "|K:" + encodeEncryptionCard(it) }.orEmpty()
        require(encryptionCard == null ||
            eventCreatedAtMillis == null && senderNickname == null) {
            "Encryption cards are only allowed in PROFILE requests"
        }
        return "R|" + uuid(relationId) + "|" + uuid(eventId) + time + nick + card
            .toByteArray(Charsets.UTF_8)
    }

    fun response(relationId: String, eventId: String, requestMessageId: String): ByteArray =
        ("A|" + uuid(relationId) + "|" + uuid(eventId) + "|" + uuid(requestMessageId))
            .toByteArray(Charsets.UTF_8)

    fun profileResponse(
        relationId: String,
        eventId: String,
        requestMessageId: String,
        nickname: String,
        encryptionCard: String? = null,
    ): ByteArray {
        val encoded = encodeNickname(nickname)
        val card = encryptionCard?.let { "|K:" + encodeEncryptionCard(it) }.orEmpty()
        return ("P|" + uuid(relationId) + "|" + uuid(eventId) + "|" +
            uuid(requestMessageId) + "|" + encoded + card).toByteArray(Charsets.UTF_8)
    }

    /** Encrypted kinds expose routing metadata only until bodyDecrypted is called. */
    fun body(message: MessageEnvelope): Body {
        require(message.payloadKind in requestKinds || message.payloadKind in responseKinds) {
            "Unsupported S1 case command"
        }
        if (message.payloadKind in encryptedKinds) {
            val envelope = S4MessageWire.decode(message.payload)
            return Body(envelope.relationId, uuid(message.eventId.value))
        }
        return parsePlaintext(message)
    }

    /** Caller must verify the sender signature and decrypt with the pinned peer ECDH key first. */
    fun bodyDecrypted(message: MessageEnvelope, plaintext: ByteArray): Body {
        require(message.payloadKind in encryptedKinds) { "Message kind does not require S4 decryption" }
        return parsePlaintext(message.copy(payload = plaintext))
    }

    private fun parsePlaintext(message: MessageEnvelope): Body {
        val text = message.payload.toString(Charsets.UTF_8)
        require(text.toByteArray(Charsets.UTF_8).contentEquals(message.payload))
        val fields = text.split('|')
        val request = message.payloadKind in requestKinds
        val profile = message.payloadKind == PROFILE_REPLY
        require((if (request) fields.size in 3..5 else
            if (profile) fields.size in 5..6 else fields.size == 4) &&
            fields[0] == (if (request) "R" else if (profile) "P" else "A")) {
            "Invalid S1 command payload"
        }
        val event = message.payloadKind in setOf(SOS, CHECKIN)
        require(!request || when (message.payloadKind) {
            SOS, CHECKIN -> fields.size in 3..5 &&
                (fields.size < 5 || fields[4].startsWith("N:"))
            CLAIM, CLOSE -> fields.size == 3 ||
                (fields.size == 4 && fields[3].startsWith("N:"))
            PROFILE -> fields.size == 3 ||
                (fields.size == 4 && fields[3].startsWith("K:"))
            else -> false
        }) { "Unexpected fields in signed request" }
        require(!profile || fields.size == 5 ||
            fields.size == 6 && fields[5].startsWith("K:")) {
            "Unexpected fields in signed profile response"
        }
        val nickname = if (profile) {
            require(fields[4].length in 1..96) { "Invalid signed profile size" }
            decodeNickname(fields[4])
        } else null
        val createdAt = if (request && event && fields.size >= 4) {
            val raw = fields[3]
            val time = raw.toLongOrNull()
            require(time != null && time in 1..253_402_300_799_000L &&
                raw == time.toString()) { "Invalid signed event timestamp" }
            time
        } else null
        val senderNickname = if (request && fields.size >= 4) {
            val encoded = if (event && fields.size == 5) fields[4]
                else if (!event && fields.size == 4 && fields[3].startsWith("N:")) fields[3]
                else null
            encoded?.let { decodeNickname(it.removePrefix("N:")) }
        } else null
        val encryptionCard = when {
            request && message.payloadKind == PROFILE && fields.size == 4 ->
                decodeEncryptionCard(fields[3].removePrefix("K:"))
            profile && fields.size == 6 ->
                decodeEncryptionCard(fields[5].removePrefix("K:"))
            else -> null
        }
        val body = Body(uuid(fields[1]), uuid(fields[2]),
            if (request) null else uuid(fields[3]), nickname, createdAt,
            senderNickname, encryptionCard)
        require(body.eventId == message.eventId.value) { "Event ID mismatch" }
        return body
    }

    fun expectedResponse(requestKind: String): Set<String> = when (requestKind) {
        SOS -> setOf(RECEIPT)
        CLAIM -> setOf(ACCEPT, REJECT)
        CLOSE -> setOf(CLOSED)
        CHECKIN -> setOf(CHECKIN_RECEIPT)
        PROFILE -> setOf(PROFILE_REPLY)
        else -> emptySet()
    }

    fun claimDecision(currentAcceptedFingerprint: String?, requestingFingerprint: String): Boolean =
        currentAcceptedFingerprint == null || currentAcceptedFingerprint == requestingFingerprint

    private fun uuid(value: String): String {
        require(value.length == 36 && UUID.fromString(value).toString() == value) {
            "Invalid relation, event or message ID"
        }
        return value
    }

    private fun validNickname(value: String): String {
        require(value.length in 1..18 && value == value.trim() && value.none {
            Character.isISOControl(it) || it == '\u202e' || it == '\u202d'
        }) { "Invalid signed display name" }
        return value
    }

    private fun encodeNickname(value: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(validNickname(value).toByteArray(Charsets.UTF_8))

    private fun decodeNickname(value: String): String {
        require(value.length in 1..96) { "Invalid signed nickname size" }
        val raw = Base64.getUrlDecoder().decode(value)
        val name = String(raw, Charsets.UTF_8)
        require(raw.contentEquals(name.toByteArray(Charsets.UTF_8))) { "Malformed signed UTF-8" }
        return validNickname(name)
    }

    private fun encodeEncryptionCard(value: String): String {
        require(value.length in 200..400 && value.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Invalid encryption public-key card"
        }
        return value
    }

    private fun decodeEncryptionCard(value: String): String {
        require(value.length in 200..400 && value.matches(Regex("[A-Za-z0-9._-]+"))) {
            "Invalid encryption public-key card"
        }
        return value
    }
}
