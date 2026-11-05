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
    private val offered = RemoteAssistTrafficMeter()
    private val rendered = RemoteAssistTrafficMeter()
    private val discarded = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var lastError = "无"
    @Volatile private var lastQueueWaitMs = -1L

    fun diagnostics(): String = "H264解码器: ${if (running.get()) "运行中" else "未运行"}; " +
        "尺寸: ${dimensions?.let { "${it.first}x${it.second}" } ?: "未知"}\n" +
        "已入队: ${offered.summary()}\n已显示: ${rendered.summary()}\n" +
        "丢弃/等待关键帧: ${discarded.get()}单元; 队列积压: ${queue.size}单元; " +
        "最近排队耗时: ${if (lastQueueWaitMs < 0) "暂无" else "${lastQueueWaitMs}ms"}\n" +
        "最近异常: $lastError"

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
        if (awaitKeyframe && !isKey && !isConfig) {
            discarded.incrementAndGet()
            return
        }
        if (!queue.offer(unit)) {
            discarded.addAndGet(queue.size.toLong() + 1)
            queue.clear()
            awaitKeyframe = true
            if (isKey || isConfig) queue.offer(unit)
        }
        offered.record(unit.bytes.size)
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
                    lastQueueWaitMs = (System.currentTimeMillis() - unit.receivedAt)
                        .coerceAtLeast(0)
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
                    rendered.record()
                    outputIndex = active.dequeueOutputBuffer(info, 0)
                }
            }
        } catch (failure: Exception) {
            lastError = "${failure.javaClass.simpleName}: ${failure.message?.take(80)}"
            // The next explicit Surface attachment is a clean decoder retry.
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            if (generation.get() == epoch) running.set(false)
        }
    }
}
