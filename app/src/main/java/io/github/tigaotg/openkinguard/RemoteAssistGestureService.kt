package io.github.tigaotg.openkinguard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Only handles signed, relationship-checked, session-scoped remote input.
 * Android accessibility must be enabled manually on the controlled phone.
 * Returned true means Android accepted the action for dispatch, not that the
 * target application visibly changed state.
 */
class RemoteAssistGestureService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var current: RemoteAssistGestureService? = null
        private val main = Handler(Looper.getMainLooper())

        fun enabled(): Boolean = current != null

        /**
         * Network receive handlers run off the main thread. Do not acknowledge a
         * remote control as accepted before Android's accessibility API responds.
         * A slow or missing UI thread is an explicit failure, not silent success.
         */
        private fun submit(action: () -> Boolean): Boolean {
            if (Looper.myLooper() == Looper.getMainLooper()) return action()
            val done = CountDownLatch(1)
            val accepted = AtomicBoolean(false)
            if (!main.post {
                    try { accepted.set(action()) }
                    finally { done.countDown() }
                }) return false
            return done.await(2, TimeUnit.SECONDS) && accepted.get()
        }

        private fun usable(sessionId: String, approved: () -> Boolean): RemoteAssistGestureService? {
            val service = current ?: return null
            return service.takeIf {
                sessionId.isNotBlank() && approved() && !it.keyguardLocked()
            }
        }

        fun tap(sessionId: String, x: Int, y: Int, approved: () -> Boolean): Boolean {
            if (x !in 0..10000 || y !in 0..10000) return false
            return submit {
                val service = usable(sessionId, approved) ?: return@submit false
                val size = service.realDisplaySize()
                val path = Path().apply {
                    moveTo(size.x * x / 10000f, size.y * y / 10000f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 75)).build()
                service.dispatchGesture(gesture, null, null)
            }
        }

        fun swipe(sessionId: String, x1: Int, y1: Int, x2: Int, y2: Int,
                  durationMs: Int, approved: () -> Boolean): Boolean {
            if (listOf(x1, y1, x2, y2).any { it !in 0..10000 } ||
                durationMs !in 120..1000) return false
            return submit {
                val service = usable(sessionId, approved) ?: return@submit false
                val size = service.realDisplaySize()
                val path = Path().apply {
                    moveTo(size.x * x1 / 10000f, size.y * y1 / 10000f)
                    lineTo(size.x * x2 / 10000f, size.y * y2 / 10000f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0,
                        durationMs.toLong())).build()
                service.dispatchGesture(gesture, null, null)
            }
        }

        fun back(sessionId: String, approved: () -> Boolean): Boolean = global(
            sessionId, AccessibilityService.GLOBAL_ACTION_BACK, approved)

        fun home(sessionId: String, approved: () -> Boolean): Boolean = global(
            sessionId, AccessibilityService.GLOBAL_ACTION_HOME, approved)

        fun recents(sessionId: String, approved: () -> Boolean): Boolean = global(
            sessionId, AccessibilityService.GLOBAL_ACTION_RECENTS, approved)

        private fun global(sessionId: String, action: Int, approved: () -> Boolean): Boolean =
            submit {
                val service = usable(sessionId, approved) ?: return@submit false
                service.performGlobalAction(action)
            }
    }

    @Suppress("DEPRECATION")
    private fun realDisplaySize(): Point {
        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        return Point().also { manager.defaultDisplay.getRealSize(it) }
    }

    private fun keyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
}
