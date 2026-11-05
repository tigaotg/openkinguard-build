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
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Single-use router QR; neither scanning nor an unauthenticated approval claims access. */
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

/** Cancels BOTH sockets and periodic checks when the user explicitly cancels. */
internal class RelayEnrollmentHandle(private val onCancel: () -> Unit) {
    fun cancel() { onCancel() }
}

/**
 * Router approval is durable, but its /join WebSocket is not. After a lost
 * notification, prove approval through the existing pin-verified /relay
 * challenge/hello/ready handshake; ready.fp MUST match our device key.
 * Never infer approval from a timeout, a TCP connection, or LuCI text.
 */
internal object S2RelayEnrollment {
    private val scheduler = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "okg-join-poll").apply { isDaemon = true }
    }
    private fun b64(data: ByteArray): String = Base64.getUrlEncoder()
        .withoutPadding().encodeToString(data)
    private fun unb64(data: String): ByteArray = Base64.getUrlDecoder().decode(data)

    fun begin(invite: RelayJoinInvite, keys: AndroidDeviceKeyStore,
              nickname: String, model: String,
              progress: (String) -> Unit, approved: () -> Unit): RelayEnrollmentHandle {
        val pin = requireNotNull(URI(invite.url).rawFragment).removePrefix("spki=")
        val relayEndpoint = invite.url.substringBefore('#')
        val joinEndpoint = relayEndpoint.removeSuffix("/relay") + "/join"
        val client = S2RelayTls.pinnedClient(pin)
        val finished = AtomicBoolean(false)
        val requestId = AtomicReference<String?>(null)
        val joinSocket = AtomicReference<WebSocket?>(null)
        val verificationSocket = AtomicReference<WebSocket?>(null)
        val checking = AtomicBoolean(false)
        val pollTask = AtomicReference<ScheduledFuture<*>?>(null)
        val verifyTask = AtomicReference<ScheduledFuture<*>?>(null)
        val deadlineTask = AtomicReference<ScheduledFuture<*>?>(null)
        val identity = keys.createOrLoad().fingerprint

        fun stopChecks() {
            pollTask.getAndSet(null)?.cancel(false)
            verifyTask.getAndSet(null)?.cancel(false)
            deadlineTask.getAndSet(null)?.cancel(false)
        }

        fun shutdown(reason: String) {
            joinSocket.getAndSet(null)?.close(1000, reason)
            verificationSocket.getAndSet(null)?.close(1000, reason)
        }

        fun confirmApproval() {
            if (!finished.compareAndSet(false, true)) return
            stopChecks()
            try {
                approved()
                progress("路由器主人已批准，家庭中继地址已保存并开始连接")
            } catch (error: Exception) {
                // Router approval is proven, but local app configuration may still fail.
                progress("路由器已批准，但手机未能保存配置：" +
                    (error.message?.take(110) ?: error.javaClass.simpleName))
            } finally {
                shutdown("enrollment verified")
            }
        }

        fun terminate(reason: String) {
            if (!finished.compareAndSet(false, true)) return
            stopChecks()
            shutdown("enrollment finished")
            progress(reason)
        }

        /** Check approval independently of the short-lived QR-request connection. */
        fun verifyByAuthenticatedRelay() {
            if (finished.get() || !checking.compareAndSet(false, true)) return
            try {
                val socket = client.newWebSocket(
                    Request.Builder().url(relayEndpoint).build(),
                    object : WebSocketListener() {
                        private fun clearThis(socket: WebSocket) {
                            if (verificationSocket.compareAndSet(socket, null))
                                checking.set(false)
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (finished.get()) return
                            try {
                                require(text.length < 4096)
                                val packet = JSONObject(text)
                                when (packet.getString("t")) {
                                    "challenge" -> {
                                        val nonce = unb64(packet.getString("n"))
                                        require(nonce.size == 32)
                                        require(webSocket.send(JSONObject()
                                            .put("t", "hello")
                                            .put("key", b64(keys.publicKeyBytes()))
                                            .put("sig", b64(keys.signRelayChallenge(nonce)))
                                            .toString()))
                                    }
                                    "ready" -> {
                                        require(packet.getString("fp") == identity)
                                        confirmApproval()
                                    }
                                }
                            } catch (_: Exception) {
                                clearThis(webSocket)
                                webSocket.close(1008, "invalid approval proof")
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable,
                                               response: Response?) {
                            clearThis(webSocket)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            clearThis(webSocket)
                        }
                    })
                verificationSocket.set(socket)
                // A broken connection must not block later checks indefinitely.
                scheduler.schedule({
                    if (!finished.get() && verificationSocket.compareAndSet(socket, null)) {
                        checking.set(false)
                        socket.close(1000, "check timeout")
                    }
                }, 6, TimeUnit.SECONDS)
            } catch (_: Exception) {
                checking.set(false)
            }
        }

        val socket = client.newWebSocket(
            Request.Builder().url(joinEndpoint).build(),
            object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (finished.get()) return
                    try {
                        require(text.length < 4096)
                        val packet = JSONObject(text)
                        when (packet.getString("t")) {
                            "join_challenge" -> {
                                val nonce = unb64(packet.getString("n"))
                                require(nonce.size == 32)
                                require(webSocket.send(JSONObject().put("t", "join")
                                    .put("token", invite.token)
                                    .put("key", b64(keys.publicKeyBytes()))
                                    .put("sig", b64(keys.signRelayJoinChallenge(nonce, invite.token)))
                                    .put("nickname", nickname.take(40))
                                    .put("model", model.take(40))
                                    .toString()))
                            }
                            "pending" -> {
                                require(packet.getString("fp") == identity)
                                requestId.set(packet.getString("id"))
                                progress("加入申请已发送，等待路由器主人批准；断线时会自动核验批准状态")
                            }
                            "approved" -> {
                                require(packet.getString("id") == requestId.get())
                                confirmApproval()
                            }
                            "rejected", "expired" -> {
                                require(packet.getString("id") == requestId.get())
                                terminate(if (packet.getString("t") == "rejected")
                                    "加入未完成：路由器主人拒绝了本次申请"
                                    else "加入未完成：二维码申请已过期")
                            }
                            else -> throw IOException("未知加入响应")
                        }
                    } catch (error: Exception) {
                        if (!finished.get()) progress("加入通道收到异常：" +
                            error.javaClass.simpleName + "；正在核验路由器授权")
                        webSocket.close(1008, "invalid join protocol")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable,
                                       response: Response?) {
                    if (!finished.get()) {
                        progress("扫码连接暂时中断（" + t.javaClass.simpleName +
                            "），正在核验路由器是否已批准；无需立刻重新扫码")
                        verifyByAuthenticatedRelay()
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (!finished.get()) {
                        progress("扫码连接已关闭（$code），仍在核验已批准设备；无需立刻重新扫码")
                        verifyByAuthenticatedRelay()
                    }
                }
            })
        joinSocket.set(socket)

        pollTask.set(scheduler.scheduleAtFixedRate({
            if (!finished.get()) requestId.get()?.let { id ->
                joinSocket.get()?.send(JSONObject().put("t", "status")
                    .put("id", id).toString())
            }
        }, 2, 3, TimeUnit.SECONDS))
        // This also repairs the case where an older app showed "加入未完成"
        // although the router already put this same public key in its allowlist.
        verifyTask.set(scheduler.scheduleAtFixedRate({
            if (!finished.get()) verifyByAuthenticatedRelay()
        }, 4, 9, TimeUnit.SECONDS))
        deadlineTask.set(scheduler.schedule({
            terminate("申请等待超时：未能通过路由器身份验证确认批准。若路由器已授权，重新扫描二维码可直接核验授权。")
        }, 420, TimeUnit.SECONDS))
        progress("正在安全连接家庭节点；批准结果将以路由器签名认证为准")
        return RelayEnrollmentHandle {
            terminate("已取消等待家庭中继批准，手机未保存本次节点配置")
        }
    }
}
