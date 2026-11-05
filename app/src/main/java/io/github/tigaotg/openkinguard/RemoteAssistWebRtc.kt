package io.github.tigaotg.openkinguard

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.os.SystemClock
import org.json.JSONObject
import org.webrtc.*
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Video-only WebRTC peer: signed OpenKinGuard packets carry OFFER/ANSWER/ICE,
 * while media travels via encrypted SRTP and ICE P2P, never through the SOS relay.
 * Screen capture is possible only with a fresh system MediaProjection grant.
 */
internal class RemoteAssistWebRtc(
    context: Context,
    private val sessionId: String,
    private val allowed: () -> Boolean,
    private val sendSignal: (String, String) -> Unit,
    private val onFirstFrame: () -> Unit,
    private val onCaptureRevoked: () -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val app = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "okg-webrtc-session").apply { isDaemon = true }
    }
    private val network = Executors.newSingleThreadExecutor { r ->
        Thread(r, "okg-webrtc-signaling").apply { isDaemon = true }
    }
    private val closed = AtomicBoolean(false)
    private val gotFrame = AtomicBoolean(false)
    private val renderLock = Any()
    @Volatile private var lastFrameAt = 0L
    @Volatile private var dimensions: Pair<Int, Int>? = null
    private var egl: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var pc: PeerConnection? = null
    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var localTrack: VideoTrack? = null
    private var remoteTrack: VideoTrack? = null
    private var renderer: SurfaceViewRenderer? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private val remoteSink = VideoSink { frame ->
        if (closed.get()) return@VideoSink
        dimensions = frame.buffer.width to frame.buffer.height
        lastFrameAt = SystemClock.elapsedRealtime()
        RemoteAssistDiagnostics.size(frame.buffer.width, frame.buffer.height)
        RemoteAssistDiagnostics.mode("WebRTC 视频 / 本机解码")
        RemoteAssistDiagnostics.onReceived(0) // Byte count is not exposed by this sink.
        RemoteAssistDiagnostics.onDecoded()
        if (gotFrame.compareAndSet(false, true)) {
            RemoteAssistDiagnostics.state("WebRTC 首帧已成功解码")
            onFirstFrame()
        }
    }

    fun videoSize(): Pair<Int, Int>? = dimensions
    fun fresh(): Boolean = lastFrameAt != 0L &&
        SystemClock.elapsedRealtime() - lastFrameAt <= 5_000L

    private fun notice(text: String) {
        RemoteAssistDiagnostics.state(text)
        onStatus(text)
    }

    private fun startFactory() {
        if (factory != null || closed.get()) return
        initializeOnce(app)
        val context = EglBase.create()
        egl = context
        factory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(context.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(context.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private fun ensurePeer(): PeerConnection {
        pc?.let { return it }
        startFactory()
        val cfg = PeerConnection.RTCConfiguration(
            listOf(PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478")
                .createIceServer()))
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        val created = requireNotNull(factory?.createPeerConnection(cfg,
            object : PeerConnection.Observer {
                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    if (!closed.get()) {
                        RemoteAssistDiagnostics.connection("WebRTC ICE " + state.name)
                        notice("WebRTC ICE: " + state.name)
                    }
                }
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidate(candidate: IceCandidate) {
                    if (closed.get()) return
                    val json = JSONObject().put("mid", candidate.sdpMid)
                        .put("line", candidate.sdpMLineIndex).put("sdp", candidate.sdp)
                    submit("ice", json.toString())
                }
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
                override fun onAddTrack(receiver: RtpReceiver, streams: Array<MediaStream>) {
                    attachRemoteTrack(receiver.track())
                }
                override fun onTrack(transceiver: RtpTransceiver) {
                    attachRemoteTrack(transceiver.receiver.track())
                }
            })) { "WebRTC PeerConnection 初始化失败" }
        pc = created
        RemoteAssistDiagnostics.connection("WebRTC ICE 建立中")
        return created
    }

    private fun attachRemoteTrack(track: MediaStreamTrack?) {
        if (track !is VideoTrack || closed.get()) return
        synchronized(renderLock) {
            if (remoteTrack === track) return
            remoteTrack?.let { old ->
                old.removeSink(remoteSink)
                renderer?.let(old::removeSink)
            }
            remoteTrack = track
            track.addSink(remoteSink)
            renderer?.let(track::addSink)
        }
    }

    fun attachRenderer(view: SurfaceViewRenderer) {
        synchronized(renderLock) {
            if (closed.get() || renderer === view) return
            val ctx = egl?.eglBaseContext ?: return
            renderer?.let { old ->
                remoteTrack?.removeSink(old)
                runCatching { old.release() }
            }
            view.init(ctx, null)
            view.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
            view.setEnableHardwareScaler(true)
            renderer = view
            remoteTrack?.addSink(view)
        }
    }

    fun detachRenderer(view: SurfaceViewRenderer) {
        synchronized(renderLock) {
            if (renderer === view) {
                remoteTrack?.removeSink(view)
                renderer = null
            }
            runCatching { view.release() }
        }
    }

    /** Called only from the foreground media-projection service after explicit consent. */
    fun startPublisher(grant: Intent, width: Int, height: Int) {
        worker.execute {
            if (closed.get() || !allowed()) return@execute
            try {
                val peer = ensurePeer()
                val screen = ScreenCapturerAndroid(grant, object : MediaProjection.Callback() {
                    override fun onStop() { if (!closed.get()) onCaptureRevoked() }
                })
                capturer = screen
                val texture = requireNotNull(SurfaceTextureHelper.create(
                    "okg-webrtc-screen", requireNotNull(egl).eglBaseContext))
                helper = texture
                val video = requireNotNull(factory).createVideoSource(true)
                source = video
                screen.initialize(texture, app, video.capturerObserver)
                val track = requireNotNull(factory).createVideoTrack("okg-screen", video)
                localTrack = track
                peer.addTrack(track, listOf("okg-screen"))
                screen.startCapture(width, height, 30)
                RemoteAssistDiagnostics.mode("WebRTC 屏幕采集 / 自动编解码")
                RemoteAssistDiagnostics.size(width, height)
                notice("WebRTC 屏幕已采集，正在建立 P2P 视频连接")
                createOffer(peer)
            } catch (failure: Exception) {
                RemoteAssistDiagnostics.error("WebRTC屏幕启动-" +
                    failure.javaClass.simpleName)
                notice("WebRTC屏幕启动失败：" + failure.javaClass.simpleName)
            }
        }
    }

    private fun submit(type: String, data: String) {
        if (closed.get()) return
        network.execute {
            if (closed.get() || !allowed()) return@execute
            runCatching { sendSignal(type, data) }.onFailure { error ->
                if (!closed.get()) RemoteAssistDiagnostics.warning(
                    "WebRTC信令发送-" + error.javaClass.simpleName)
            }
        }
    }

    private fun createOffer(peer: PeerConnection) {
        peer.createOffer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                setLocal(peer, description, "offer")
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String) {
                RemoteAssistDiagnostics.error("WebRTC Offer 创建失败")
            }
            override fun onSetFailure(reason: String) {}
        }, MediaConstraints())
    }

    private fun createAnswer(peer: PeerConnection) {
        peer.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) {
                setLocal(peer, description, "answer")
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(reason: String) {
                RemoteAssistDiagnostics.error("WebRTC Answer 创建失败")
            }
            override fun onSetFailure(reason: String) {}
        }, MediaConstraints())
    }

    private fun setLocal(peer: PeerConnection, description: SessionDescription,
                         type: String) {
        peer.setLocalDescription(object : SdpObserver {
            override fun onCreateSuccess(value: SessionDescription) {}
            override fun onSetSuccess() { submit(type, description.description) }
            override fun onCreateFailure(reason: String) {}
            override fun onSetFailure(reason: String) {
                RemoteAssistDiagnostics.error("WebRTC 本地SDP设置失败")
            }
        }, description)
    }

    fun receive(type: String, text: String) {
        worker.execute {
            if (closed.get() || !allowed()) return@execute
            try {
                val peer = ensurePeer()
                if (type == "ice") {
                    val data = JSONObject(text)
                    val candidate = IceCandidate(data.optString("mid"),
                        data.getInt("line"), data.getString("sdp"))
                    if (peer.remoteDescription == null) pendingIce.add(candidate)
                    else peer.addIceCandidate(candidate)
                } else {
                    val description = SessionDescription(
                        if (type == "offer") SessionDescription.Type.OFFER
                        else SessionDescription.Type.ANSWER, text)
                    peer.setRemoteDescription(object : SdpObserver {
                        override fun onCreateSuccess(value: SessionDescription) {}
                        override fun onSetSuccess() {
                            worker.execute {
                                if (closed.get()) return@execute
                                pendingIce.forEach(peer::addIceCandidate)
                                pendingIce.clear()
                                if (type == "offer") createAnswer(peer)
                            }
                        }
                        override fun onCreateFailure(reason: String) {}
                        override fun onSetFailure(reason: String) {
                            RemoteAssistDiagnostics.error("WebRTC 远端SDP设置失败")
                        }
                    }, description)
                }
            } catch (failure: Exception) {
                RemoteAssistDiagnostics.error("WebRTC信令处理-" +
                    failure.javaClass.simpleName)
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        network.shutdownNow()
        worker.execute {
            synchronized(renderLock) {
                remoteTrack?.removeSink(remoteSink)
                renderer?.let { remoteTrack?.removeSink(it) }
                remoteTrack = null
                renderer = null
            }
            runCatching { capturer?.stopCapture() }
            runCatching { capturer?.dispose() }
            runCatching { helper?.dispose() }
            runCatching { pc?.close() }
            runCatching { pc?.dispose() }
            runCatching { localTrack?.dispose() }
            runCatching { source?.dispose() }
            runCatching { factory?.dispose() }
            runCatching { egl?.release() }
            pc = null
            worker.shutdown()
        }
    }

    companion object {
        private var initialized = false
        @Synchronized private fun initializeOnce(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .createInitializationOptions())
            initialized = true
        }
    }
}
