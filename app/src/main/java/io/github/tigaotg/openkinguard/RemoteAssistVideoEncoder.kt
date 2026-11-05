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
                        send(configPresentation, MediaCodec.BUFFER_FLAG_CODEC_CONFIG,
                            requireNotNull(configBytes))
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
                        send(info.presentationTimeUs, flags and 31, data)
                        report("720p硬件视频流已通过局域网发送")
                    } else report("关键帧超过视频限额，等待下一帧")
                } finally { encoder.releaseOutputBuffer(index, false) }
            }
        } catch (failure: Exception) {
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
