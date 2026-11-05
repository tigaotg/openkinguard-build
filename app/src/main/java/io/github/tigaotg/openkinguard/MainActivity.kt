package io.github.tigaotg.openkinguard

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.compose.ui.graphics.toArgb

/** One Chinese-language APK; the active manager survives UI tab switching. */
class MainActivity : ComponentActivity() {
    private var incomingWidgetAction by mutableStateOf<String?>(null)
    private var incomingRemoteInvite by mutableStateOf<String?>(null)
    private var incomingMedicalReminder by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingWidgetAction = widgetAction(intent)
        incomingRemoteInvite = remoteInvite(intent)
        incomingMedicalReminder = intent?.action == MedicalReminders.ACTION_HEALTH
        setContent {
            val themes = remember { GuardThemeRepository(applicationContext) }
            val theme = themes.selected
            val colors = themes.colorScheme()
            MaterialTheme(colorScheme = colors) {
                SideEffect {
                    // Gesture navigation on Android 15+ can remain system-controlled.
                    if (android.os.Build.VERSION.SDK_INT < 35) {
                        @Suppress("DEPRECATION")
                        window.navigationBarColor = colors.surface.toArgb()
                    }
                    WindowCompat.getInsetsController(window, window.decorView)
                        .isAppearanceLightNavigationBars = theme.mode != "dark"
                }
                S1CompactSection(themes, incomingWidgetAction,
                    onWidgetActionConsumed = { incomingWidgetAction = null },
                    incomingRemoteInvite = incomingRemoteInvite,
                    onRemoteInviteConsumed = { incomingRemoteInvite = null },
                    incomingMedicalReminder = incomingMedicalReminder,
                    onMedicalReminderConsumed = { incomingMedicalReminder = false })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingWidgetAction = widgetAction(intent)
        incomingRemoteInvite = remoteInvite(intent)
        incomingMedicalReminder = intent.action == MedicalReminders.ACTION_HEALTH
    }

    private fun widgetAction(intent: Intent?): String? = when (intent?.action) {
        GuardWidgetPublisher.ACTION_SOS -> GuardWidgetPublisher.ACTION_SOS
        GuardWidgetPublisher.ACTION_CHECKIN -> GuardWidgetPublisher.ACTION_CHECKIN
        else -> null
    }

    private fun remoteInvite(intent: Intent?): String? =
        intent?.dataString?.takeIf { intent.action == Intent.ACTION_VIEW &&
            it.startsWith("openkinguard://invite?data=") && it.length <= 8_000 }
}
