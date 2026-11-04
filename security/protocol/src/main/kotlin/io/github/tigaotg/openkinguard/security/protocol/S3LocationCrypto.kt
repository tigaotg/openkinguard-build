package io.github.tigaotg.openkinguard.security.protocol

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Per-request ephemeral P-256 ECDH + HKDF-SHA256 + AES-256-GCM, on top of signed envelopes. */
object S3LocationCrypto {
    private val b64encoder = Base64.getUrlEncoder().withoutPadding()
    private val b64decoder = Base64.getUrlDecoder()
    private val random = SecureRandom()

    fun ephemeral(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    fun encodePublic(publicKey: PublicKey): String = b64encoder.encodeToString(publicKey.encoded)

    fun encrypt(fix: S3LocationWire.Fix, recipientPublic: String,
                relationId: String, eventId: String, requestId: String): S3LocationWire.EncryptedFix {
        val sendingKey = ephemeral()
        val recipient = PairingQrProtocol.publicKey(b64decoder.decode(recipientPublic))
        val aad = associatedData(relationId, eventId, requestId)
        val key = derive(sendingKey.private, recipient, aad)
        val nonce = ByteArray(12).also(random::nextBytes)
        val plaintext = "${fix.latitudeE7}|${fix.longitudeE7}|${fix.accuracyCm}|" +
            "${fix.capturedAtMillis}|${fix.provider}"
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.US_ASCII))
        return S3LocationWire.EncryptedFix(encodePublic(sendingKey.public),
            b64encoder.encodeToString(nonce), b64encoder.encodeToString(sealed))
    }

    fun decrypt(recipientPrivate: PrivateKey, encrypted: S3LocationWire.EncryptedFix,
                relationId: String, eventId: String, requestId: String): S3LocationWire.Fix {
        val sender = PairingQrProtocol.publicKey(b64decoder.decode(encrypted.ephemeralPublicKey))
        val nonce = b64decoder.decode(encrypted.nonce)
        require(nonce.size == 12)
        val cipherBytes = b64decoder.decode(encrypted.ciphertext)
        require(cipherBytes.size in 17..160)
        val aad = associatedData(relationId, eventId, requestId)
        val key = derive(recipientPrivate, sender, aad)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(aad)
        val bytes = cipher.doFinal(cipherBytes) // Tampering or wrong key always fails here.
        require(bytes.size in 9..100 && bytes.all { it.toInt() in 32..126 })
        val fields = bytes.toString(Charsets.US_ASCII).split('|')
        require(fields.size == 5) { "Invalid decrypted position shape" }
        val latitude = fields[0].toInt().also { require(it.toString() == fields[0]) }
        val longitude = fields[1].toInt().also { require(it.toString() == fields[1]) }
        val accuracy = fields[2].toInt().also { require(it.toString() == fields[2]) }
        val capturedAt = fields[3].toLong().also { require(it.toString() == fields[3]) }
        return S3LocationWire.Fix(latitude, longitude, accuracy, capturedAt, fields[4])
    }

    private fun derive(ownPrivate: PrivateKey, peerPublic: PublicKey,
                       associated: ByteArray): ByteArray {
        val secret = KeyAgreement.getInstance("ECDH").run {
            init(ownPrivate)
            doPhase(peerPublic, true)
            generateSecret()
        }
        val salt = MessageDigest.getInstance("SHA-256").digest(associated)
        val extract = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(secret)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(extract, "HmacSHA256"))
            doFinal("OpenKinGuard|S3|location|key|v1\u0001".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun associatedData(relation: String, event: String, request: String): ByteArray =
        "OpenKinGuard|S3|location|v1|$relation|$event|$request"
            .toByteArray(Charsets.US_ASCII)
}
