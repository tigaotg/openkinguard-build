package io.github.tigaotg.openkinguard

import android.os.Build
import android.os.SystemClock
import java.util.ArrayDeque
import java.util.Locale

/**
 * Ephemeral, privacy-preserving diagnostics for ONE manually approved remote-assist session.
 * No IP addresses, public keys, relation IDs, session tokens or screen content are recorded.
 * FPS is measured from actual events over a rolling five-second window, not target FPS.
 */
internal object RemoteAssistDiagnostics {
    private const val WINDOW_MS = 5_000L
    private var startedAt = SystemClock.elapsedRealtime()
    private var role = "未开始"
    private var codec = "未初始化"
    private var transport = "未连接"
    private var dimensions = "未知"
    private var status = "尚未采集"
    private var lastError = "无"
    private var active = false
    private val encodedEvents = ArrayDeque<Long>()
    private val sentEvents = ArrayDeque<Long>()
    private val receivedEvents = ArrayDeque<Long>()
    private val decodedEvents = ArrayDeque<Long>()
    private var sentBytes = 0L
    private var receivedBytes = 0L
    private var sentFrames = 0L
    private var receivedFrames = 0L
    private var decodedFrames = 0L
    private var droppedFrames = 0L
    private var currentQueue = 0
    private var peakQueue = 0
    private var sendCalls = 0L
    private var sendDurationTotalMs = 0L
    private var sendDurationMaxMs = 0L
    private var lastVideoAt = 0L
    private val events = ArrayDeque<String>()

    @Synchronized fun begin(newRole: String) {
        startedAt = SystemClock.elapsedRealtime()
        role = newRole
        codec = "未初始化"
        transport = "等待连接"
        dimensions = "未知"
        status = "等待系统授权 / 连接"
        lastError = "无"
        active = true
        encodedEvents.clear()
        sentEvents.clear()
        receivedEvents.clear()
        decodedEvents.clear()
        sentBytes = 0
        receivedBytes = 0
        sentFrames = 0
        receivedFrames = 0
        decodedFrames = 0
        droppedFrames = 0
        currentQueue = 0
        peakQueue = 0
        sendCalls = 0
        sendDurationTotalMs = 0
        sendDurationMaxMs = 0
        lastVideoAt = 0
        events.clear()
        event("开始新一轮远程协助")
    }

    @Synchronized fun mode(value: String) {
        codec = value.take(64)
        event("编码模式：$codec")
    }

    @Synchronized fun connection(value: String) {
        transport = value.take(64)
        event("连接：$transport")
    }

    @Synchronized fun size(width: Int, height: Int) {
        dimensions = "${width}x${height}"
    }

    @Synchronized fun state(value: String) { status = value.take(90) }

    @Synchronized fun onEncoded() { stamp(encodedEvents) }

    @Synchronized fun onSent(bytes: Int, durationMs: Long) {
        stamp(sentEvents)
        sentFrames++
        sentBytes += bytes
        sendCalls++
        sendDurationTotalMs += durationMs
        sendDurationMaxMs = maxOf(sendDurationMaxMs, durationMs)
        lastVideoAt = SystemClock.elapsedRealtime()
    }

    @Synchronized fun onReceived(bytes: Int) {
        stamp(receivedEvents)
        receivedFrames++
        receivedBytes += bytes
        lastVideoAt = SystemClock.elapsedRealtime()
    }

    @Synchronized fun onDecoded() { stamp(decodedEvents); decodedFrames++ }

    @Synchronized fun onDropped() { droppedFrames++ }

    @Synchronized fun onQueue(depth: Int) {
        currentQueue = depth
        peakQueue = maxOf(peakQueue, depth)
    }

    @Synchronized fun error(type: String) {
        // Error category only: never embed socket addresses or arbitrary exception messages.
        lastError = type.take(64)
        event("异常：$lastError")
    }

    @Synchronized fun finish() { active = false; event("会话结束") }

    private fun stamp(buffer: ArrayDeque<Long>) {
        val now = SystemClock.elapsedRealtime()
        buffer.addLast(now)
        while (buffer.isNotEmpty() && now - buffer.peekFirst() > WINDOW_MS) buffer.removeFirst()
    }

    private fun event(value: String) {
        events.addLast("${(SystemClock.elapsedRealtime() - startedAt) / 1000}s: $value")
        while (events.size > 14) events.removeFirst()
    }

    private fun fps(buffer: ArrayDeque<Long>, now: Long): String {
        while (buffer.isNotEmpty() && now - buffer.peekFirst() > WINDOW_MS) buffer.removeFirst()
        val observed = minOf(WINDOW_MS, maxOf(1000L, now - startedAt))
        return String.format(Locale.US, "%.1f", buffer.size * 1000.0 / observed)
    }

    /** Bounded performance-only summary for the approved family controller. */
    @Synchronized fun peerSummary(): String {
        val now = SystemClock.elapsedRealtime()
        val average = if (sendCalls == 0L) "暂无" else
            "${sendDurationTotalMs / sendCalls}ms"
        return "被控制端: ${Build.MANUFACTURER} ${Build.MODEL}; " +
            "Android ${Build.VERSION.RELEASE}/API ${Build.VERSION.SDK_INT}\n" +
            "视频: $codec; 尺寸: $dimensions; 网络: $transport\n" +
            "编码FPS(最近5秒): ${fps(encodedEvents, now)}\n" +
            "发送FPS(最近5秒): ${fps(sentEvents, now)}\n" +
            "累计发送: $sentFrames 帧 / $sentBytes 字节\n" +
            "单次发送耗时: 平均$average, 最大${sendDurationMaxMs}ms\n" +
            "已丢弃: $droppedFrames; 状态: $status\n最近异常: $lastError"
    }

    @Synchronized fun report(): String {
        val now = SystemClock.elapsedRealtime()
        val age = if (lastVideoAt == 0L) "未收到视频事件"
            else "${now - lastVideoAt}ms 前"
        val avgSend = if (sendCalls == 0L) "暂无"
            else "${sendDurationTotalMs / sendCalls}ms"
        return """
OpenKinGuard 远程协助诊断（本机）
测试版：0.9.22
设备：${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})
角色：$role
会话：${if (active) "进行中" else "未进行"}
本次运行时长：${(now - startedAt) / 1000}s
网络：$transport
视频模式：$codec
画面分辨率：$dimensions
实时状态：$status

【最近5秒实际事件，不是目标帧率】
编码输出：${fps(encodedEvents, now)} FPS
网络发送：${fps(sentEvents, now)} FPS
网络接收：${fps(receivedEvents, now)} FPS
解码输出：${fps(decodedEvents, now)} FPS
已发送帧 / 字节：$sentFrames / $sentBytes
已接收帧 / 字节：$receivedFrames / $receivedBytes
已显示解码帧：$decodedFrames
已知丢弃帧：$droppedFrames
解码队列：$currentQueue / 峰值 $peakQueue
每帧阻塞发送耗时：平均 $avgSend，最大 ${sendDurationMaxMs}ms
最近视频活动：$age
最近异常：$lastError

【测量边界】
以上为本机实际计数；获得对方屏幕共享授权后控制端还会接收对方的简要编码状态。
TCP 传输无按包丢失率统计；未实现独立 RTT 探针。
不同手机时钟没有校准，端到端单向延迟未测量，不能由时间戳直接推算。
视频编码设置目标：约 720p / 30 FPS / 1.8 Mbps，实际值以计数为准。

【事件】
${events.joinToString("\n")}

隐私：此报告不包含 IP 地址、端口对应地址、会话 ID、密钥或屏幕内容。
""".trimIndent()
    }
}
