package io.github.tigaotg.openkinguard

import io.github.tigaotg.openkinguard.security.protocol.NostrSchnorr
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Small strict NIP-01 event codec. Nostr signatures do not replace device pairing checks. */
internal object S2NostrWire {
    const val KIND = 30078 // NIP-78 application-specific event.

    private fun hex(bytes: ByteArray) = bytes.joinToString("") {
        "%02x".format(it.toInt() and 255)
    }
    private fun unhex(input: String): ByteArray {
        require(input.length % 2 == 0 && input.matches(Regex("[0-9a-f]+")))
        return input.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun sign(secret: ByteArray, target: String, content: String, marker: String,
             expiresAtMillis: Long): JSONObject {
        require(target.matches(Regex("[0-9a-f]{64}")))
        require(marker.length in 3..100 && content.length in 5..19_000)
        val public = hex(NostrSchnorr.publicKey(secret))
        val timestamp = System.currentTimeMillis() / 1000L
        val tags = JSONArray().put(JSONArray().put("k").put(target))
            .put(JSONArray().put("d").put(marker))
            // NIP-01: every element in a tag must be a JSON string.
            // NIP-40: the expiration timestamp is decimal text, not a JSON number.
            .put(JSONArray().put("expiration").put((expiresAtMillis / 1000L).toString()))
        val id = digest(public, timestamp, KIND, tags, content)
        val signature = NostrSchnorr.sign(id, secret)
        return JSONObject().put("id", hex(id)).put("pubkey", public)
            .put("created_at", timestamp).put("kind", KIND)
            .put("tags", tags).put("content", content).put("sig", hex(signature))
    }

    fun verify(json: JSONObject, nowMillis: Long = System.currentTimeMillis()): Boolean =
        runCatching {
            require(json.getInt("kind") == KIND)
            val created = json.getLong("created_at")
            require(created in (nowMillis / 1000 - 25 * 60 * 60)..(nowMillis / 1000 + 60))
            val content = json.getString("content")
            require(content.length <= 19_000)
            val pub = json.getString("pubkey")
            val id = unhex(json.getString("id"))
            val signature = unhex(json.getString("sig"))
            require(pub.length == 64 && id.size == 32 && signature.size == 64)
            val tags = json.getJSONArray("tags")
            require(tags.length() <= 15)
            for (i in 0 until tags.length()) {
                val tag = tags.getJSONArray(i)
                require(tag.length() in 1..10)
                for (j in 0 until tag.length()) require(tag.get(j) is String)
                require(tag.getString(0).isNotEmpty())
                if (tag.getString(0) == "expiration") {
                    require(tag.length() >= 2)
                    val expirySeconds = tag.getString(1).toLongOrNull() ?: error("invalid expiry")
                    require(expirySeconds > nowMillis / 1000L)
                }
            }
            val expected = digest(pub, created, KIND, tags, content)
            MessageDigest.isEqual(id, expected) &&
                NostrSchnorr.verify(id, unhex(pub), signature)
        }.getOrDefault(false)

    fun routeTag(event: JSONObject): String? {
        val tags = event.optJSONArray("tags") ?: return null
        for (i in 0 until tags.length()) {
            val tag = tags.optJSONArray(i) ?: continue
            if (tag.optString(0) == "k") return tag.optString(1)
        }
        return null
    }

    private fun digest(pub: String, time: Long, kind: Int, tags: JSONArray,
                       content: String): ByteArray {
        val canonical = JSONArray().put(0).put(pub).put(time).put(kind).put(tags).put(content)
            .toString().toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(canonical)
    }
}
