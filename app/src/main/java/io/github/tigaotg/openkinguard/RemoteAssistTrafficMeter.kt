package io.github.tigaotg.openkinguard

import java.util.ArrayDeque

/** Short-lived, aggregate traffic counters. No image bytes, identifiers or addresses retained. */
internal class RemoteAssistTrafficMeter {
    private val samples = ArrayDeque<Pair<Long, Int>>()
    private var count = 0L
    private var totalBytes = 0L
    private var lastAt = 0L

    @Synchronized fun record(bytes: Int = 0) {
        val now = System.currentTimeMillis()
        count++
        totalBytes += bytes.coerceAtLeast(0)
        lastAt = now
        samples.addLast(now to bytes.coerceAtLeast(0))
        trim(now)
    }

    @Synchronized fun summary(): String {
        val now = System.currentTimeMillis()
        trim(now)
        val bytes = samples.sumOf { it.second.toLong() }
        val age = if (lastAt == 0L) "尚无数据" else "${now - lastAt}ms"
        return "最近5秒: ${samples.size}单元，${"%.1f".format(samples.size / 5.0)}次/秒，" +
            "${"%.2f".format(bytes * 8.0 / 5_000_000.0)}Mbps；" +
            "累计$count 单元/${totalBytes / 1024}KiB；距最近一次$age"
    }

    @Synchronized fun reset() {
        samples.clear()
        count = 0L
        totalBytes = 0L
        lastAt = 0L
    }

    private fun trim(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().first > 5000L) {
            samples.removeFirst()
        }
    }
}
