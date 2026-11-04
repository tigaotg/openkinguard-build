package io.github.tigaotg.openkinguard.core.engine

/** Relative age labels used for verified GPS fixes, never an online guarantee. */
object FriendlyElapsedTime {
    fun since(timestampMillis: Long, nowMillis: Long): String {
        if (timestampMillis <= 0L) return "时间未知"
        val elapsed = (nowMillis - timestampMillis).coerceAtLeast(0L)
        val seconds = elapsed / 1_000L
        return when {
            seconds < 10L -> "刚刚"
            seconds < 60L -> "${(seconds / 10) * 10}秒前"
            seconds < 120L -> "1分钟前"
            seconds < 3_600L -> "${seconds / 60}分钟前"
            seconds < 86_400L -> "${seconds / 3_600}小时前"
            else -> "${seconds / 86_400}天前"
        }
    }
}
