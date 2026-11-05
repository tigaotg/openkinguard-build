package io.github.tigaotg.openkinguard

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Decoder belongs to the visible controller Surface, not to an always-on capture service. */
internal class RemoteAssistVideoDecoder {
    private val queue = ArrayBlockingQueue<RemoteAssistVideoFrames.Unit>(12)
    private val running = AtomicBoolean(false)
    private val generation = AtomicLong(0)
    @Volatile private var thread: Thread? = null
    @Volatile private var dimensions: Pair<Int, Int>? = null
    @Volatile private var awaitKeyframe = true

    @Synchronized fun attach(surface: Surface, initialConfig: RemoteAssistVideoFrames.Unit?) {
        detach()
        queue.clear()
        initialConfig?.let { queue.offer(it) }
        running.set(true)
        awaitKeyframe = true
        val epoch = generation.incrementAndGet()
        val job = Thread({ decode(surface, epoch) }, "okg-avc-decoder")
        job.isDaemon = true
        thread = job
        job.start()
    }

    fun offer(unit: RemoteAssistVideoFrames.Unit) {
        if (!running.get()) return
        val isConfig = unit.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKey = unit.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (awaitKeyframe && !isKey && !isConfig) return
        if (!queue.offer(unit)) {
            queue.clear()
            awaitKeyframe = true
            if (isKey || isConfig) queue.offer(unit)
        }
        if (isKey) awaitKeyframe = false
    }

    @Synchronized fun detach() {
        generation.incrementAndGet()
        running.set(false)
        thread?.interrupt()
        thread = null
        queue.clear()
        dimensions = null
    }

    private fun decode(surface: Surface, epoch: Long) {
        var codec: MediaCodec? = null
        var lastSize: Pair<Int, Int>? = null
        val info = MediaCodec.BufferInfo()
        try {
            while (running.get() && generation.get() == epoch &&
                !Thread.currentThread().isInterrupted) {
                val unit = try { queue.poll(60, java.util.concurrent.TimeUnit.MILLISECONDS) }
                    catch (_: InterruptedException) { break }
                if (unit != null) {
                    val size = unit.width to unit.height
                    if (codec == null || lastSize != size) {
                        runCatching { codec?.stop() }
                        runCatching { codec?.release() }
                        codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                            unit.width, unit.height)
                        fmt.setInteger("low-latency", 1) // optional, ignored on older decoders
                        codec.configure(fmt, surface, null, 0)
                        codec.start()
                        lastSize = size
                        dimensions = size
                    }
                    val active = requireNotNull(codec)
                    val inputIndex = active.dequeueInputBuffer(5_000)
                    if (inputIndex >= 0) {
                        val buffer = requireNotNull(active.getInputBuffer(inputIndex))
                        buffer.clear()
                        if (unit.bytes.size <= buffer.remaining()) {
                            buffer.put(unit.bytes)
                            active.queueInputBuffer(inputIndex, 0, unit.bytes.size,
                                unit.presentationUs, unit.flags and
                                    MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
                        } else active.queueInputBuffer(inputIndex, 0, 0, 0, 0)
                    }
                }
                val active = codec ?: continue
                var outputIndex = active.dequeueOutputBuffer(info, 0)
                while (outputIndex >= 0) {
                    active.releaseOutputBuffer(outputIndex, true)
                    outputIndex = active.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (_: Exception) {
            // The next explicit Surface attachment is a clean decoder retry.
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (generation.get() == epoch) running.set(false)
        }
    }
}
