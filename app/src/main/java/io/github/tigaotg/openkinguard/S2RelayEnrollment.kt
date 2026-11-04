package io.github.tigaotg.openkinguard

import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single-use router QR. A phone only gains access AFTER the router owner approves. */
internal data class RelayJoinInvite(val url: String, val token: String) {
    companion object {
        fun fromQr(raw: String): RelayJoinInvite {
            require(raw.length in 80..1024) { "这不是有效的家庭节点加入二维码" }
            val payload = JSONObject(raw)
            require(payload.optString("kind") == "OpenKinGuardRelayJoin" &&
                payload.optInt("v") == 1) { "二维码不是 OpenKinGuard 家庭节点加入邀请" }
            val url = S2RelayClient.normalize(payload.getString("url"))
            require(URI(url).rawFragment?.matches(Regex("spki=[0-9a-f]{64}")) == true) {
                "扫码加入必须包含路由器 TLS 公钥指纹"
            }
            val token = payload.getString("token")
            require(token.matches(Regex("[A-Za-z0-9_-]{32}"))) { "邀请码格式无效" }
            return RelayJoinInvite(url, token)
        }
    }
}

internal object S2RelayEnrollment {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "okg-join-poll").apply { isDaemon = true }
    }
    private fun b64(data: ByteArray): String = Base64.getUrlEncoder()
        .withoutPadding().encodeToString(data)
    private fun unb64(data: String): ByteArray = Base64.getUrlDecoder().decode(data)

    fun begin(invite: RelayJoinInvite, keys: AndroidDeviceKeyStore,
              nickname: String, model: String,
              progress: (String) -> Unit, approved: () -> Unit): WebSocket {
        val pin = requireNotNull(URI(invite.url).rawFragment).removePrefix("spki=")
        val joinEndpoint = invite.url.substringBefore('#').removeSuffix("/relay") + "/join"
        val finished = AtomicBoolean(false)
        val requestId = AtomicReference<String?>(null)
        val socket = S2RelayTls.pinnedClient(pin).newWebSocket(
            Request.Builder().url(joinEndpoint).build(), object : WebSocketListener() {
                private fun fail(reason: String) {
                    if (finished.compareAndSet(false, true)) progress("加入未完成：$reason；请重新扫码申请")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        require(text.length < 4096)
                        val packet = JSONObject(text)
                        when (packet.getString("t")) {
                            "join_challenge" -> {
                                val nonce = unb64(packet.getString("n"))
                                require(nonce.size == 32)
                                webSocket.send(JSONObject().put("t", "join")
                                    .put("token", invite.token)
                                    .put("key", b64(keys.publicKeyBytes()))
                                    .put("sig", b64(keys.signRelayJoinChallenge(nonce, invite.token)))
                                    .put("nickname", nickname.take(40))
                                    .put("model", model.take(40))
                                    .toString())
                            }
                            "pending" -> {
                                require(packet.getString("fp") == keys.createOrLoad().fingerprint)
                                requestId.set(packet.getString("id"))
                                progress("申请已发送，请回到路由器页面刷新‘待批准设备’并核对指纹")
                            }
                            "approved" -> {
                                require(packet.getString("id") == requestId.get())
                                if (finished.compareAndSet(false, true)) {
                                    try {
                                        approved()
                                        progress("路由器主人已批准，家庭中继已自动保存并开始连接")
                                    } catch (error: Exception) {
                                        progress("路由器已批准，但手机未能保存配置：${error.message}")
                                    }
                                }
                                webSocket.close(1000, "enrollment complete")
                            }
                            "rejected", "expired" -> {
                                require(packet.getString("id") == requestId.get())
                                fail(if (packet.getString("t") == "rejected") "路由器主人拒绝" else "申请已过期")
                                webSocket.close(1000, "enrollment finished")
                            }
                            else -> throw IOException("未知加入响应")
                        }
                    } catch (error: Exception) {
                        fail(error.message ?: "加入协议错误")
                        webSocket.close(1008, "protocol error")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    fail(t.message ?: "无法连接家庭节点")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    fail("连接已断开（$code）")
                }
            })
        val poll = scheduler.scheduleAtFixedRate({
            val id = requestId.get()
            if (!finished.get() && id != null) socket.send(
                JSONObject().put("t", "status").put("id", id).toString())
        }, 2, 3, TimeUnit.SECONDS)
        scheduler.schedule({
            poll.cancel(false)
            if (finished.compareAndSet(false, true)) {
                progress("加入申请等待超时，请回到路由器生成新的二维码")
                socket.close(1000, "timeout")
            }
        }, 420, TimeUnit.SECONDS)
        progress("正在通过 TLS 公钥固定验证并连接家庭节点…")
        return socket
    }
}
