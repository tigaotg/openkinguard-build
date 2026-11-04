package io.github.tigaotg.openkinguard.security.protocol

import org.bouncycastle.asn1.sec.SECNamedCurves
import org.bouncycastle.math.ec.ECPoint
import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/** BIP-340 on secp256k1 for the NIP-01 transport identity, NOT the trusted P-256 device ID.
 *
 * The cryptographic curve arithmetic comes from Bouncy Castle. In particular this does
 * not reinterpret the Android Keystore P-256 identity as a Nostr account or export it.
 */
object NostrSchnorr {
    private val params = requireNotNull(SECNamedCurves.getByName("secp256k1"))
    private val order = params.n
    private val field = BigInteger("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEFFFFFC2F", 16)
    private val generator = params.g
    private val random = SecureRandom()
    private val zero = BigInteger.ZERO

    fun createSecret(): ByteArray = ByteArray(32).also { out ->
        do { random.nextBytes(out) } while (!validSecret(out))
    }

    fun validSecret(bytes: ByteArray): Boolean = bytes.size == 32 &&
        BigInteger(1, bytes).let { it > zero && it < order }

    fun publicKey(secret: ByteArray): ByteArray {
        require(validSecret(secret)) { "Invalid Nostr secret key" }
        return fixed32(generator.multiply(BigInteger(1, secret)).normalize().affineXCoord.toBigInteger())
    }

    fun sign(message: ByteArray, secret: ByteArray, auxRandom: ByteArray =
        ByteArray(32).also(random::nextBytes)): ByteArray {
        require(message.size == 32 && auxRandom.size == 32 && validSecret(secret))
        val d0 = BigInteger(1, secret)
        val p = generator.multiply(d0).normalize()
        val d = if (p.affineYCoord.toBigInteger().testBit(0)) order.subtract(d0) else d0
        val public = fixed32(p.affineXCoord.toBigInteger())
        val t = xor(fixed32(d), taggedHash("BIP0340/aux", auxRandom))
        val k0 = BigInteger(1, taggedHash("BIP0340/nonce", t + public + message)).mod(order)
        require(k0 != zero) { "Invalid Schnorr nonce" }
        val rPoint = generator.multiply(k0).normalize()
        val k = if (rPoint.affineYCoord.toBigInteger().testBit(0)) order.subtract(k0) else k0
        val r = fixed32(rPoint.affineXCoord.toBigInteger())
        val e = BigInteger(1, taggedHash("BIP0340/challenge", r + public + message)).mod(order)
        val signature = r + fixed32(k.add(e.multiply(d)).mod(order))
        check(verify(message, public, signature)) { "Schnorr self-verification failed" }
        return signature
    }

    fun verify(message: ByteArray, publicKey: ByteArray, signature: ByteArray): Boolean =
        runCatching {
            if (message.size != 32 || publicKey.size != 32 || signature.size != 64) return false
            val x = BigInteger(1, publicKey)
            val r = BigInteger(1, signature.copyOfRange(0, 32))
            val s = BigInteger(1, signature.copyOfRange(32, 64))
            if (x >= field || r >= field || s >= order) return false
            val public = evenYPoint(x) ?: return false
            val e = BigInteger(1, taggedHash("BIP0340/challenge",
                signature.copyOfRange(0, 32) + publicKey + message)).mod(order)
            val rp = generator.multiply(s).subtract(public.multiply(e)).normalize()
            !rp.isInfinity && !rp.affineYCoord.toBigInteger().testBit(0) &&
                rp.affineXCoord.toBigInteger() == r
        }.getOrDefault(false)

    private fun evenYPoint(x: BigInteger): ECPoint? {
        val ySquared = x.modPow(BigInteger.valueOf(3), field).add(BigInteger.valueOf(7)).mod(field)
        var y = ySquared.modPow(field.add(BigInteger.ONE).shiftRight(2), field)
        if (y.multiply(y).mod(field) != ySquared) return null
        if (y.testBit(0)) y = field.subtract(y)
        return params.curve.createPoint(x, y)
    }

    private fun taggedHash(tag: String, data: ByteArray): ByteArray {
        val hashedTag = sha256(tag.toByteArray(Charsets.UTF_8))
        return sha256(hashedTag + hashedTag + data)
    }

    private fun sha256(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun fixed32(value: BigInteger): ByteArray {
        require(value >= zero)
        val bytes = value.toByteArray().dropWhile { it == 0.toByte() }
        require(bytes.size <= 32)
        return ByteArray(32 - bytes.size) + bytes.toByteArray()
    }
}
