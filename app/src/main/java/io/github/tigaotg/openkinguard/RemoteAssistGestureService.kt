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

/**
 * Optional, manually enabled by the phone owner in Android Accessibility Settings.
 * Never receives third-party intents. The approval callback is checked again on
 * the UI thread immediately before each input, so an ended session cannot type.
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

        fun tap(sessionId: String, x: Int, y: Int, approved: () -> Boolean) {
            if (sessionId.isBlank() || x !in 0..10000 || y !in 0..10000) return
            main.post {
                val service = current ?: return@post
                if (!approved() || service.keyguardLocked()) return@post
                val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val size = Point().also { manager.defaultDisplay.getRealSize(it) }
                val path = Path().apply {
                    moveTo(size.x * x / 10000f, size.y * y / 10000f)
                }
                val gesture = GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 75)).build()
                service.dispatchGesture(gesture, null, null)
            }
        }

        fun swipe(sessionId: String, x1: Int, y1: Int, x2: Int, y2: Int,
                  durationMs: Int, approved: () -> Boolean) {
            if (sessionId.isBlank() || listOf(x1, y1, x2, y2).any { it !in 0..10000 } ||
                durationMs !in 120..1000) return
            main.post {
                val service = current ?: return@post
                if (!approved() || service.keyguardLocked()) return@post
                val manager = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                @Suppress("DEPRECATION")
                val size = Point().also { manager.defaultDisplay.getRealSize(it) }
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

        fun back(sessionId: String, approved: () -> Boolean) = global(
            sessionId, AccessibilityService.GLOBAL_ACTION_BACK, approved)

        fun home(sessionId: String, approved: () -> Boolean) = global(
            sessionId, AccessibilityService.GLOBAL_ACTION_HOME, approved)

        private fun global(sessionId: String, action: Int, approved: () -> Boolean) {
            if (sessionId.isBlank()) return
            main.post {
                val service = current ?: return@post
                if (approved() && !service.keyguardLocked()) service.performGlobalAction(action)
            }
        }
    }

    private fun keyguardLocked(): Boolean =
        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
}
