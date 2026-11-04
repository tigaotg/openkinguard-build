package io.github.tigaotg.openkinguard

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Explicit SPKI pinning for a family-owned, self-signed relay.
 *
 * Only the user-entered #spki=SHA256(SubjectPublicKeyInfo) activates this
 * alternative trust model. Public WSS URLs continue using system PKI.
 * This is NEVER a permissive/insecure trust manager: a mismatching key fails
 * the TLS handshake before any application data or device identity is sent.
 */
internal object S2RelayTls {
    private val hexPin = Regex("[0-9a-f]{64}")

    fun pinFromPublicKey(key: PublicKey): String = MessageDigest.getInstance("SHA-256")
        .digest(key.encoded).joinToString("") { byte -> "%02x".format(byte.toInt() and 255) }

    fun verifyPin(key: PublicKey, expected: String): Boolean {
        if (!hexPin.matches(expected)) return false
        val actual = pinFromPublicKey(key)
        return MessageDigest.isEqual(actual.toByteArray(Charsets.US_ASCII),
            expected.toByteArray(Charsets.US_ASCII))
    }

    fun pinnedClient(pin: String): OkHttpClient {
        require(hexPin.matches(pin)) { "节点公钥指纹必须是64位小写十六进制字符" }
        val manager = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("家庭中继不会向客户端颁发证书")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val certificate = chain.firstOrNull()
                    ?: throw CertificateException("家庭中继未提供 TLS 证书")
                certificate.checkValidity()
                if (!verifyPin(certificate.publicKey, pin)) {
                    throw CertificateException("节点公钥指纹与手机保存的指纹不一致")
                }
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(manager), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(context.socketFactory, manager)
            // Hostname is an address that may change. The pinned key, checked
            // both by the trust manager and here, is the sole node identity.
            .hostnameVerifier { _, session ->
                try {
                    val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: return@hostnameVerifier false
                    cert.checkValidity()
                    verifyPin(cert.publicKey, pin)
                } catch (_: Exception) {
                    false
                }
            }
            .pingInterval(25, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
