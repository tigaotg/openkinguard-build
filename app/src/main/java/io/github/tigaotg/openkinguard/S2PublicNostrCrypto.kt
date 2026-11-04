package io.github.tigaotg.openkinguard

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import io.github.tigaotg.openkinguard.security.protocol.NostrSchnorr
import io.github.tigaotg.openkinguard.security.protocol.PairingQrProtocol
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The existing signing identity is NEVER exported or changed by the Nostr adapter. */
internal class S2PublicNostrCrypto(context: Context, private val deviceKeys: AndroidDeviceKeyStore) {
    private val identityFile = AtomicFile(File(context.filesDir, "s2-nostr-transport-identity-v1.json"))
    private val random = SecureRandom()
    private val storageAlias = "openkinguard.s2.nostr.transport.wrap.v1"
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    data class LocalIdentity(val nostrSecret: ByteArray, val agreementPrivate: PrivateKey,
                             val agreementPublic: String) {
        val nostrPublic: String
            get() = NostrSchnorr.publicKey(nostrSecret).joinToString("") {
                "%02x".format(it.toInt() and 255)
            }
    }

    data class Sealed(val ephemeralKey: String, val nonce: String, val ciphertext: String) {
        fun json() = JSONObject().put("epk", ephemeralKey).put("iv", nonce)
            .put("ct", ciphertext)
        companion object {
            fun parse(json: JSONObject): Sealed = Sealed(json.getString("epk"),
                json.getString("iv"), json.getString("ct"))
        }
    }

    @Synchronized
    fun load(): LocalIdentity {
        val json = try {
            JSONObject(String(identityFile.readFully(), Charsets.UTF_8))
        } catch (_: FileNotFoundException) {
            val agreement = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"))
            }.generateKeyPair()
            val secret = NostrSchnorr.createSecret()
            val doc = JSONObject().put("v", 1)
                .put("secret", b64(secret))
                .put("private", b64(agreement.private.encoded))
                .put("public", b64(agreement.public.encoded))
            val blob = protect(doc.toString().toByteArray(Charsets.UTF_8))
            var stream: java.io.FileOutputStream? = null
            try {
                stream = identityFile.startWrite()
                stream.write(JSONObject().put("v", 1).put("blob", blob).toString().toByteArray())
                identityFile.finishWrite(stream)
            } catch (error: Exception) {
                if (stream != null) identityFile.failWrite(stream)
                throw error
            }
            doc
        }
        // The file is either the unwrapped new identity above or an authenticated wrapper.
        val inner = if (json.has("blob")) JSONObject(String(unprotect(json.getString("blob")),
            Charsets.UTF_8)) else json
        require(inner.getInt("v") == 1)
        val secret = unb64(inner.getString("secret"))
        require(NostrSchnorr.validSecret(secret))
        val privateKey = KeyFactory.getInstance("EC").generatePrivate(
            PKCS8EncodedKeySpec(unb64(inner.getString("private"))))
        val publicKey = inner.getString("public")
        require(unb64(publicKey).size in 70..180)
        return LocalIdentity(secret, privateKey, publicKey)
    }

    private fun wrappingKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(storageAlias, null) as? SecretKey
        if (existing != null) return existing
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(KeyGenParameterSpec.Builder(storageAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256).build())
            }.generateKey()
    }

    private fun protect(plaintext: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return b64(cipher.iv + cipher.doFinal(plaintext))
    }

    private fun unprotect(blob: String): ByteArray {
        val bytes = unb64(blob)
        require(bytes.size in 29..4096)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }

    fun card(identity: LocalIdentity, validForMs: Long = 24 * 60 * 60_000L): JSONObject {
        val fp = deviceKeys.createOrLoad().fingerprint
        val issued = System.currentTimeMillis()
        val exp = issued + validForMs
        val body = cardBody(fp, identity.nostrPublic, identity.agreementPublic, issued, exp)
        return JSONObject().put("v", 1).put("fp", fp)
            .put("npub", identity.nostrPublic).put("epub", identity.agreementPublic)
            .put("iat", issued).put("exp", exp)
            .put("sig", b64(deviceKeys.signNostrContactCard(body)))
    }

    /** Only the pinned QR-paired P-256 key may authorize a public relay contact card. */
    fun verifyCard(card: JSONObject, pinnedPeer: SavedPeer, nostrEventPubkey: String,
                   now: Long = System.currentTimeMillis()): Boolean = runCatching {
        require(card.getInt("v") == 1)
        val fp = card.getString("fp")
        val nostr = card.getString("npub")
        val epub = card.getString("epub")
        val issued = card.getLong("iat")
        val expires = card.getLong("exp")
        require(fp == pinnedPeer.peerFingerprint && nostr == nostrEventPubkey)
        require(issued in (now - 48 * 60 * 60_000L)..(now + 60_000L))
        require(expires in now..(issued + 24 * 60 * 60_000L))
        require(epub.length in 70..300)
        PairingQrProtocol.publicKey(unb64(epub)) // Validate curve/key shape.
        val public = PairingQrProtocol.publicKey(unb64(pinnedPeer.peerPublicKeyBase64))
        val proof = unb64(card.getString("sig"))
        val body = cardBody(fp, nostr, epub, issued, expires)
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(public)
            update("OpenKinGuard|nostr-contact|v1|".toByteArray(Charsets.US_ASCII))
            update(body)
            verify(proof)
        }
    }.getOrDefault(false)

    private fun cardBody(fp: String, npub: String, epub: String, issued: Long, exp: Long): ByteArray =
        "OpenKinGuard|nostr-contact|$fp|$npub|$epub|$issued|$exp"
            .toByteArray(Charsets.US_ASCII)

    fun newReplyKey(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun encodePublic(key: PublicKey): String = b64(key.encoded)

    fun seal(plaintext: ByteArray, recipientPublic: String, aad: ByteArray): Sealed {
        require(plaintext.size in 40..15_000 && aad.size in 30..350)
        val temporary = newReplyKey()
        val recipient = PairingQrProtocol.publicKey(unb64(recipientPublic))
        val key = derive(temporary.private, recipient, aad)
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(aad)
        return Sealed(encodePublic(temporary.public), b64(iv), b64(cipher.doFinal(plaintext)))
    }

    fun open(sealed: Sealed, recipientPrivate: PrivateKey, aad: ByteArray): ByteArray {
        val nonce = unb64(sealed.nonce)
        val payload = unb64(sealed.ciphertext)
        require(nonce.size == 12 && payload.size in 56..17_000)
        val originator = PairingQrProtocol.publicKey(unb64(sealed.ephemeralKey))
        val key = derive(recipientPrivate, originator, aad)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        return cipher.doFinal(payload)
    }

    private fun derive(privateKey: PrivateKey, publicKey: PublicKey, aad: ByteArray): ByteArray {
        val shared = KeyAgreement.getInstance("ECDH").run {
            init(privateKey)
            doPhase(publicKey, true)
            generateSecret()
        }
        val salt = MessageDigest.getInstance("SHA-256").digest(aad)
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(shared)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            doFinal("OpenKinGuard|nostr-ecdh|AES256GCM|v1\u0001".toByteArray(Charsets.US_ASCII))
        }
    }

    fun associated(target: String, id: String, role: String, expiry: Long): ByteArray =
        "OpenKinGuard|nostr-envelope|v1|$target|$id|$role|$expiry"
            .toByteArray(Charsets.US_ASCII)

    private fun b64(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun unb64(value: String): ByteArray = decoder.decode(value)
}
