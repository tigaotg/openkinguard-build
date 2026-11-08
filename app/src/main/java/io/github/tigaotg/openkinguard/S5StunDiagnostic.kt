package io.github.tigaotg.openkinguard

import android.content.Context
import android.net.ConnectivityManager
import io.github.tigaotg.openkinguard.core.engine.S5StunCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Explicit foreground test of UDP NAT mapping. No application payload is sent.
 * Returned mappings are snapshots, NOT reusable ICE candidates: the socket is
 * deliberately closed after the test until S5 has a persistent UDP data plane.
 */
internal object S5StunDiagnostic {
    private val random = SecureRandom()
    private val servers = listOf("stun.miwifi.com", "stun.hitv.com", "stun.qq.com")

    data class Observation(val server: String, val mapped: S5StunCodec.MappedEndpoint,
                           val localPort: Int, val rttMillis: Long)

    fun run(context: Context): String {
        val connectivity = context.applicationContext.getSystemService(
            Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivity.activeNetwork
            ?: return "当前没有默认网络，无法执行STUN探测。"
        return DatagramSocket(null).use { socket ->
            socket.reuseAddress = false
            socket.bind(InetSocketAddress(0))
            network.bindSocket(socket)
            socket.soTimeout = 1400
            val observations = mutableListOf<Observation>()
            val errors = mutableListOf<String>()
            for (domain in servers) {
                val addresses = runCatching { InetAddress.getAllByName(domain).toList() }
                    .getOrElse {
                        errors += domain + "：DNS解析失败"
                        continue
                    }
                val targets = listOfNotNull(
                    addresses.firstOrNull { it.address.size == 4 },
                    addresses.firstOrNull { it.address.size == 16 }
                )
                if (targets.isEmpty()) {
                    errors += domain + "：没有A/AAAA地址"
                    continue
                }
                for (address in targets) {
                    val transactionId = ByteArray(12).also(random::nextBytes)
                    val request = S5StunCodec.bindingRequest(transactionId)
                    val started = System.nanoTime()
                    try {
                        socket.send(DatagramPacket(request, request.size, address, 3478))
                        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1400)
                        var mapped: S5StunCodec.MappedEndpoint? = null
                        while (System.nanoTime() < deadline && mapped == null) {
                            val response = DatagramPacket(ByteArray(2048), 2048)
                            try { socket.receive(response) }
                            catch (_: SocketTimeoutException) { break }
                            if (response.address != address || response.port != 3478) continue
                            mapped = S5StunCodec.mappedAddress(
                                response.data.copyOf(response.length), transactionId)
                        }
                        if (mapped != null) {
                            observations += Observation(domain, mapped, socket.localPort,
                                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
                        } else {
                            errors += domain + "（" + (if (address.address.size == 16) "IPv6" else "IPv4") +
                                "）：无有效STUN回应"
                        }
                    } catch (error: Exception) {
                        errors += domain + "：" + error.javaClass.simpleName.take(32)
                    }
                }
            }
            if (observations.isEmpty()) {
                "UDP地址探测失败：" + errors.joinToString("；") +
                    "。请确认当前网络可访问UDP/3478；失败不代表没有公网IPv6。"
            } else {
                val unique = observations.map { it.mapped }.distinct()
                val lines = observations.joinToString("；") {
                    val address = if (it.mapped.ipv6) "[" + it.mapped.address + "]"
                        else it.mapped.address
                    it.server + "→" + address + ":" + it.mapped.port +
                        "，约" + it.rttMillis + "毫秒"
                }
                "已观察到外部地址（仅本机显示）：" + lines + "。" +
                    (if (unique.size > 1) "不同STUN目标映射不同，NAT可能不利于P2P穿透。"
                    else "两次映射一致也不能证明P2P可达。") +
                    "此为一次性检测，UDP测试端口已关闭；还没有建立对端直连。"
            }
        }
    }

}
