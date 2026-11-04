package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.PairingQrProtocol
import io.github.tigaotg.openkinguard.security.protocol.PairingStep
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.MessageDigest
import java.util.Base64

/** Shareable invitation, never an authorization to read SOS or location. */
internal data class RemotePairingInvitation(
    val frame: String,
    val contact: JSONObject,
    val relays: List<String>,
    val room: String,
    val expiresAt: Long,
    val link: String,
)

internal object RemotePairingInvitationCodec {
    private const val PREFIX = "okg-remote-v1:"
    private const val URL_PREFIX = "openkinguard://invite?data="

    fun room(sessionId: String): String = MessageDigest.getInstance("SHA-256")
        .digest("OpenKinGuard|remote-room|v1|$sessionId"
            .toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    fun aad(room: String, step: String, expiresAt: Long): ByteArray {
        require(room.matches(Regex("[0-9a-f]{64}")))
        require(step in setOf("RESPONSE", "GRANT", "ACK", "RECEIPT", "DONE"))
        return "OpenKinGuard|remote-handshake|v1|$room|$step|$expiresAt"
            .toByteArray(Charsets.US_ASCII)
    }

    fun encode(frame: String, contact: JSONObject, relays: List<String>,
               now: Long = System.currentTimeMillis()): String {
        val signed = PairingQrProtocol.decodeAndVerify(frame, now).frame
        require(signed.step == PairingStep.INVITE)
        require(contact.optString("fp") == signed.senderFingerprint)
        require(contact.optString("npub").matches(Regex("[0-9a-f]{64}")))
        require(relays.isNotEmpty() && relays.size <= 3)
        val body = JSONObject().put("v", 1).put("frame", frame)
            .put("card", contact).put("relays", JSONArray(relays))
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(body.toString().toByteArray(Charsets.UTF_8))
        require(encoded.length <= 7_000) { "远程邀请数据过长" }
        return URL_PREFIX + encoded
    }

    fun parse(input: String, now: Long = System.currentTimeMillis()): RemotePairingInvitation {
        val raw = input.trim()
        val encoded = when {
            raw.startsWith(URL_PREFIX) -> raw.removePrefix(URL_PREFIX).substringBefore('&')
            raw.startsWith(PREFIX) -> raw.removePrefix(PREFIX)
            else -> throw IllegalArgumentException("不是 OpenKinGuard 远程邀请链接")
        }
        require(encoded.length in 150..7_000 && encoded.matches(Regex("[A-Za-z0-9_-]+"))) {
            "远程邀请数据格式错误"
        }
        val bytes = Base64.getUrlDecoder().decode(encoded)
        require(bytes.size in 100..5_250) { "远程邀请数据长度异常" }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(json.getInt("v") == 1) { "不支持的远程邀请版本" }
        val frame = json.getString("frame")
        val signed = PairingQrProtocol.decodeAndVerify(frame, now).frame
        require(signed.step == PairingStep.INVITE) { "只能接收首次邀请消息" }
        val contact = json.getJSONObject("card")
        require(contact.optString("fp") == signed.senderFingerprint) {
            "邀请者签名指纹与节点名片不一致"
        }
        val array = json.getJSONArray("relays")
        require(array.length() in 1..3)
        val relays = (0 until array.length()).map { array.getString(it) }
        relays.forEach { endpoint ->
            val uri = URI(endpoint)
            require(uri.scheme == "wss" && !uri.host.isNullOrBlank() &&
                uri.path.isNullOrBlank() && uri.query == null && uri.fragment == null) {
                "远程邀请中的公共节点地址无效"
            }
        }
        return RemotePairingInvitation(frame, contact, relays,
            room(signed.sessionId), signed.expiresAtEpochMillis, URL_PREFIX + encoded)
    }
}
