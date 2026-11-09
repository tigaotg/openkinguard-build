package io.github.tigaotg.openkinguard

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Never do database I/O on the UI, WebSocket, network callback or heartbeat thread. */
internal object ConnectionHistoryRecorder {
    private val queued = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "okg-connection-history").apply { isDaemon = true }
    }

    fun schedule(context: Context, manager: S1GuardManager) {
        if (!queued.compareAndSet(false, true)) return
        val app = context.applicationContext
        try {
            worker.execute {
                try {
                    ConnectionHistoryStore.capture(app, snapshot(app, manager))
                } catch (error: Exception) {
                    Log.w("OpenKinGuard", "Connection history capture failed", error)
                } finally {
                    queued.set(false)
                }
            }
        } catch (error: Exception) {
            queued.set(false)
            Log.w("OpenKinGuard", "Connection history worker unavailable", error)
        }
    }

    private fun snapshot(context: Context, manager: S1GuardManager): Map<String, String> {
        val state = linkedMapOf<String, String>()
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as ConnectivityManager
        val capabilities = connectivity.activeNetwork?.let {
            connectivity.getNetworkCapabilities(it)
        }
        state["network/type"] = when {
            capabilities == null -> "offline"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        state["network/validated"] =
            if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
                "yes" else "no"
        state["app/foreground"] = if (S1Runtime.isUiVisible()) "yes" else "no"
        state["background/consent"] =
            if (S1Runtime.isBackgroundListening()) "enabled" else "disabled"
        state["background/healthy"] =
            if (S1LanService.runtimeProbe().healthy) "yes" else "no"
        state["tcp/listener"] = manager.tcpListenerHealth().name.lowercase()
        val current = manager.snapshot()
        state["lan/listener"] = if (current.listening) "active" else "inactive"
        state["relay/enabled"] = if (current.history.relayEnabled) "yes" else "no"
        manager.privateRelayDiagnostics().forEach { node ->
            val prefix = "relay/" + node.index
            state["$prefix/socket"] = if (node.socketConnected) "connected" else "disconnected"
            state["$prefix/auth"] = if (node.identityAuthenticated) "verified" else "unverified"
            state["$prefix/offline_spool"] = if (node.offlineSpoolSupported) "supported" else "unknown"
            state["$prefix/failure"] = if (node.identityAuthenticated) "none"
                else ConnectionHistoryPolicy.failureCategory(node.lastFailureReason)
            state["$prefix/phase"] = ConnectionHistoryPolicy.relayPhase(node.status,
                node.identityAuthenticated, node.socketConnected)
        }
        state["nostr/enabled"] = if (current.history.nostrRelayEnabled) "yes" else "no"
        state["nostr/socket"] = if (manager.publicRelayReady()) "connected" else "disconnected"
        state["udp/enabled"] = if (current.history.directUdpListenerEnabled) "yes" else "no"
        state["udp/socket"] = if (manager.directActive()) "active" else "inactive"
        manager.connectionPeerDiagnostics().take(32).forEach { peer ->
            val prefix = "peer/" + ConnectionHistoryPolicy.peerAlias(peer.relationId)
            state["$prefix/lan"] = if (peer.lanVerified) "verified" else "unverified"
            state["$prefix/relay"] = if (peer.relayVerified) "verified" else "unverified"
            state["$prefix/udp"] = if (peer.directVerified) "verified" else "unverified"
        }
        return state
    }
}
