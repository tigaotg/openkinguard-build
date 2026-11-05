package io.github.tigaotg.openkinguard

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Android hardware AVC encoder: no JPEG bitmaps, disk writes, audio, or relay transport. */
internal class RemoteAssistVideoEncoder(
    val width: Int, val height: Int,
    private val send: (presentationUs: Long, flags: Int, bytes: ByteArray) -> Unit,
    private val report: (String) -> Unit
) {
    private val running = AtomicBoolean(false)
    private var codec: MediaCodec? = null
    private var input: Surface? = null
    private var worker: Thread? = null
    private val encoded = RemoteAssistTrafficMeter()
    private val transmitted = RemoteAssistTrafficMeter()
    @Volatile private var errorState = "无"
    @Volatile private var lastWriteMs = -1L

    fun diagnostics(): String = "H264编码器: ${width}x$height, 目标30fps\n" +
        "编码输出: ${encoded.summary()}\n已发送: ${transmitted.summary()}\n" +
        "最近单帧发送耗时: ${if (lastWriteMs < 0) "暂无" else "${lastWriteMs}ms"}\n" +
        "编码状态: ${if (running.get()) "运行中" else "已停止"}; 最近异常: $errorState"

    fun start(): Surface {
        require(!running.get())
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                width, height)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            format.setInteger(MediaFormat.KEY_BIT_RATE, 1_800_000)
            format.setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            format.setInteger("max-bframes", 0)
            format.setFloat("max-fps-to-encoder", 30f)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val surface = encoder.createInputSurface()
            encoder.start()
            codec = encoder
            input = surface
            running.set(true)
            worker = Thread({ drain(encoder) }, "okg-assist-avc-30fps").apply {
                isDaemon = true
                start()
            }
            return surface
        } catch (failure: Exception) {
            runCatching { encoder.release() }
            throw failure
        }
    }

    private fun drain(encoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var configBytes: ByteArray? = null
        var configPresentation = 0L
        try {
            while (running.get()) {
                val index = encoder.dequeueOutputBuffer(info, 40_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Many Android 10 encoders publish SPS/PPS in outputFormat,
                    // without a separate BUFFER_FLAG_CODEC_CONFIG access unit.
                    val format = encoder.outputFormat
                    val csd = ByteArrayOutputStream()
                    for (name in listOf("csd-0", "csd-1")) {
                        val bytes = format.getByteBuffer(name) ?: continue
                        val copy = ByteArray(bytes.remaining())
                        bytes.get(copy)
                        csd.write(copy)
                    }
                    if (csd.size() > 0) {
                        configBytes = csd.toByteArray()
                        val sentAt = android.os.SystemClock.elapsedRealtime()
                        send(configPresentation, MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                            requireNotNull(configBytes))
                        lastWriteMs = android.os.SystemClock.elapsedRealtime() - sentAt
                    }
                    continue
                }
                if (index < 0) continue
                try {
                    if (info.size <= 0) continue
                    val buffer = encoder.getOutputBuffer(index) ?: continue
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = ByteArray(info.size)
                    buffer.get(data)
                    val flags = info.flags
                    if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        configBytes = data
                        configPresentation = info.presentationTimeUs
                    }
                    // Refresh the decoder's SPS/PPS before every keyframe so a
                    // controller can close/reopen its viewing window without a new grant.
                    if (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) {
                        configBytes?.let { send(configPresentation,
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG, it) }
                    }
                    if (data.size <= 288_000) {
                        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) encoded.record(data.size)
                        val sentAt = android.os.SystemClock.elapsedRealtime()
                        send(info.presentationTimeUs, flags and 31, data)
                        lastWriteMs = android.os.SystemClock.elapsedRealtime() - sentAt
                        if (flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0)
                            transmitted.record(data.size)
                        report("720p硬件视频流已通过局域网发送")
                    } else {
                        errorState = "编码帧大于288KB"
                        report("关键帧超过视频限额，等待下一帧")
                    }
                } finally { encoder.releaseOutputBuffer(index, false) }
            }
        } catch (failure: Exception) {
            errorState = "${failure.javaClass.simpleName}: ${failure.message?.take(80)}"
            if (running.get()) report("硬件视频流中断：${failure.message?.take(70)
                ?: failure.javaClass.simpleName}")
        } finally {
            running.set(false)
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { input?.release() }
        }
    }

    fun close() {
        running.set(false)
        worker?.interrupt()
        if (Thread.currentThread() !== worker) runCatching { worker?.join(700) }
    }
}
