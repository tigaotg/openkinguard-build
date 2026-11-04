package io.github.tigaotg.openkinguard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import io.github.tigaotg.openkinguard.core.model.DeviceId
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import io.github.tigaotg.openkinguard.security.protocol.PairingFrame
import io.github.tigaotg.openkinguard.security.protocol.PairingQrProtocol
import io.github.tigaotg.openkinguard.security.protocol.SignedProtocol
import io.github.tigaotg.openkinguard.core.model.MessageEnvelope

data class LocalDevicePublicIdentity(val deviceId: DeviceId, val fingerprint: String)

/**
 * S1 identity bootstrap. Private key material stays in AndroidKeyStore.
 * Only public keys are exchanged during explicit QR pairing; S1-03 permits
 * foreground-signed diagnostic LAN packets, never automatic SOS messages.
 */
class AndroidDeviceKeyStore {
    private val alias = "openkinguard.s1.device.signing.v1"

    @Synchronized
    fun createOrLoad(): LocalDevicePublicIdentity {
        val keyStore = openStore()
        if (!keyStore.containsAlias(alias)) {
            val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            generator.initialize(KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
            ).setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build())
            generator.generateKeyPair()
        }
        val publicKey = requireNotNull(openStore().getCertificate(alias)?.publicKey) {
            "Signing public key unavailable"
        }
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(publicKey.encoded)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return LocalDevicePublicIdentity(DeviceId("okg-$fingerprint"), fingerprint)
    }

    /** Confirms private-key signing and public-key verification on this device. */
    @Synchronized
    fun selfTest(): Boolean {
        createOrLoad()
        val keystore = openStore()
        val privateKey = keystore.getKey(alias, null) as? PrivateKey ?: return false
        val publicKey = keystore.getCertificate(alias)?.publicKey ?: return false
        val probe = "OpenKinGuard|local-keystore-self-test|v1".toByteArray(Charsets.UTF_8)
        val signed = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(probe)
            sign()
        }
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(probe)
            verify(signed)
        }
    }

    @Synchronized
    fun publicKeyBytes(): ByteArray {
        createOrLoad()
        return requireNotNull(openStore().getCertificate(alias)?.publicKey?.encoded)
    }

    @Synchronized
    fun signPairingFrame(frame: PairingFrame): String {
        createOrLoad()
        val privateKey = openStore().getKey(alias, null) as PrivateKey
        // Signing happens inside Android Keystore. Private bytes never leave it.
        return PairingQrProtocol.encode(frame, privateKey)
    }

    @Synchronized
    fun signMessage(message: MessageEnvelope): ByteArray {
        createOrLoad()
        val privateKey = openStore().getKey(alias, null) as PrivateKey
        return SignedProtocol.sign(message, privateKey)
    }

    /** Authenticate the relay session without ever exporting the Android Keystore private key. */
    @Synchronized
    fun signRelayChallenge(nonce: ByteArray): ByteArray {
        require(nonce.size == 32) { "Invalid relay challenge" }
        createOrLoad()
        val privateKey = openStore().getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update("OpenKinGuard|relay-auth-v1|".toByteArray(Charsets.UTF_8))
            update(nonce)
            sign()
        }
    }

    /** A join request proves Keystore possession and binds this exact QR token. */
    @Synchronized
    fun signRelayJoinChallenge(nonce: ByteArray, token: String): ByteArray {
        require(nonce.size == 32 && token.length in 20..100)
        createOrLoad()
        val privateKey = openStore().getKey(alias, null) as PrivateKey
        val tokenDigest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update("OpenKinGuard|relay-join-v1|".toByteArray(Charsets.US_ASCII))
            update(nonce)
            update(tokenDigest)
            sign()
        }
    }

    /** Bind a separately generated public relay key to the already-paired P-256 identity. */
    @Synchronized
    fun signNostrContactCard(card: ByteArray): ByteArray {
        require(card.size in 80..1500)
        createOrLoad()
        val privateKey = openStore().getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update("OpenKinGuard|nostr-contact|v1|".toByteArray(Charsets.US_ASCII))
            update(card)
            sign()
        }
    }

    /** Prove that the inviter actually received the helper's final signed ACK. */
    @Synchronized
    fun signRemotePairingReceipt(ackDigest: String): ByteArray {
        require(ackDigest.matches(Regex("[0-9a-f]{64}")))
        createOrLoad()
        val privateKey = openStore().getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update("OpenKinGuard|remote-pairing-ack-receipt|v1|"
                .toByteArray(Charsets.US_ASCII))
            update(ackDigest.toByteArray(Charsets.US_ASCII))
            sign()
        }
    }

    /** Helper proves it has persisted the relationship, not only sent an ACK. */
    @Synchronized
    fun signRemotePairingDone(ackDigest: String): ByteArray {
        require(ackDigest.matches(Regex("[0-9a-f]{64}")))
        createOrLoad()
        val privateKey = openStore().getKey(alias, null) as PrivateKey
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update("OpenKinGuard|remote-pairing-saved|v1|"
                .toByteArray(Charsets.US_ASCII))
            update(ackDigest.toByteArray(Charsets.US_ASCII))
            sign()
        }
    }

    private fun openStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
}

