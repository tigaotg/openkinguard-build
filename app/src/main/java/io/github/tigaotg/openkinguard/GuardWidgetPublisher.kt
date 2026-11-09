package io.github.tigaotg.openkinguard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.graphics.toArgb
import android.content.Intent
import android.graphics.Color
import android.text.format.DateFormat
import android.widget.RemoteViews

/** Classic RemoteViews widget: no remote command, no widget-side network traffic. */
class GuardStatusWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        GuardWidgetPublisher.redraw(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: android.os.Bundle) {
        GuardWidgetPublisher.redraw(context)
    }
}

class GuardQuickWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        GuardWidgetPublisher.redraw(context)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager,
        appWidgetId: Int, newOptions: android.os.Bundle) {
        GuardWidgetPublisher.redraw(context)
    }
}

/** Extra lightweight widgets: buttons only open the app's confirmation UI. */
class GuardSosWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        GuardWidgetPublisher.redraw(context)
    }
}

class GuardCheckinWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        GuardWidgetPublisher.redraw(context)
    }
}

class GuardPresenceWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        GuardWidgetPublisher.redraw(context)
    }
}

/** A signed heartbeat is the only source of a positive online result. */
internal object GuardWidgetPublisher {
    const val ACTION_SOS = "io.github.tigaotg.openkinguard.WIDGET_TEST_SOS"
    const val ACTION_CHECKIN = "io.github.tigaotg.openkinguard.WIDGET_CHECKIN"
    private const val MIN_PUBLISH_MS = 10_000L
    private var lastPublishElapsed = 0L
    @Volatile private var lastCheckedAt = 0L
    @Volatile private var listening = false
    @Volatile private var visibleCount = 0
    @Volatile private var visibleNames: List<String> = emptyList()

    fun anyPlaced(context: Context): Boolean {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        return widgetIds(app, manager, GuardStatusWidgetProvider::class.java).isNotEmpty() ||
            widgetIds(app, manager, GuardQuickWidgetProvider::class.java).isNotEmpty() ||
            widgetIds(app, manager, GuardSosWidgetProvider::class.java).isNotEmpty() ||
            widgetIds(app, manager, GuardCheckinWidgetProvider::class.java).isNotEmpty() ||
            widgetIds(app, manager, GuardPresenceWidgetProvider::class.java).isNotEmpty()
    }

    private fun widgetIds(context: Context, manager: AppWidgetManager,
        provider: Class<*>): IntArray = manager.getAppWidgetIds(ComponentName(context, provider))

    /** Called only by the active app or its user-enabled listening service. */
    fun publishFromManager(context: Context, manager: S1GuardManager) {
        if (!anyPlaced(context)) return
        val elapsed = android.os.SystemClock.elapsedRealtime()
        synchronized(this) {
            if (elapsed - lastPublishElapsed in 0 until MIN_PUBLISH_MS) return
            lastPublishElapsed = elapsed
        }
        runCatching {
            val snapshot = manager.snapshot()
            val now = System.currentTimeMillis()
            val online = manager.relations().filter { peer ->
                GuardWidgetPresencePolicy.isSignedOnline(
                    snapshot.history.lastVerifiedAt[peer.relationId],
                    peer.relationId in snapshot.verifiedRelations ||
                        peer.relationId in snapshot.relayVerifiedRelations ||
                        peer.relationId in snapshot.directVerifiedRelations,
                    now, snapshot.history.lossWarningMinutes,
                    snapshot.history.lossCriticalMinutes)
            }.distinctBy { it.peerFingerprint }
            // Only nicknames may be shown on a user-added launcher widget.
            // Never cache IP/MAC, BLE readings, phone numbers, secrets, or locations.
            val names = online.take(3).map { peer ->
                manager.displayPeerNickname(peer.relationId, peer.peerFingerprint)
                    .replace('\n', ' ').replace('\r', ' ').take(20)
            }
            // Never persist relationship nicknames to an unencrypted widget cache.
            // The launcher holds only the last RemoteViews snapshot; process restart
            // requires reopening the app to verify devices again.
            lastCheckedAt = now
            listening = snapshot.listening || snapshot.relayConnected
            visibleCount = online.size
            visibleNames = names
            redraw(context)
        }
    }

    /** On app/service stop, never keep displaying an unqualified ONLINE badge. */
    fun markInactive(context: Context) {
        if (!anyPlaced(context)) return
        listening = false
        redraw(context)
    }

    fun redraw(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val systemDark = app.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val palette = GuardThemeRepository(app).colorScheme(systemDark)
        val background = palette.surface.toArgb()
        val foreground = palette.onSurface.toArgb()
        val accent = palette.primary.toArgb()
        val checkedAt = lastCheckedAt
        val fresh = GuardWidgetPresencePolicy.isCachedSnapshotFresh(checkedAt,
            listening, System.currentTimeMillis())
        val count = visibleCount.coerceAtLeast(0)
        val names = visibleNames
        val timeLabel = if (checkedAt == 0L) "尚未读取本机签名状态" else
            "截至 ${DateFormat.format("HH:mm", checkedAt)} · 非实时"

        for (id in widgetIds(app, manager, GuardStatusWidgetProvider::class.java)) {
            val view = RemoteViews(app.packageName, R.layout.widget_guard_status)
            view.setInt(R.id.widget_status_root, "setBackgroundColor", background)
            view.setTextColor(R.id.widget_status_title, foreground)
            view.setTextColor(R.id.widget_status_online, accent)
            view.setTextColor(R.id.widget_status_names, foreground)
            view.setTextColor(R.id.widget_status_time, foreground)
            view.setTextViewText(R.id.widget_status_online, when {
                !fresh -> "● 当前在线状态待验证"
                count == 0 -> "● 暂无已验签在线设备"
                else -> "● 最近验签在线 $count 台"
            })
            view.setTextViewText(R.id.widget_status_names, when {
                !fresh -> "打开应用查看；后台监听需手动开启"
                names.isEmpty() -> "暂无可以确认在线的家人"
                else -> "在线：${names.joinToString("、")}" + if (count > names.size) " 等" else ""
            })
            view.setTextViewText(R.id.widget_status_time, timeLabel)
            view.setOnClickPendingIntent(R.id.widget_status_root, actionIntent(app, id, null))
            view.setOnClickPendingIntent(R.id.widget_status_sos, actionIntent(app, id, ACTION_SOS))
            view.setOnClickPendingIntent(R.id.widget_status_checkin, actionIntent(app, id, ACTION_CHECKIN))
            manager.updateAppWidget(id, view)
        }
        for (id in widgetIds(app, manager, GuardQuickWidgetProvider::class.java)) {
            val view = RemoteViews(app.packageName, R.layout.widget_guard_quick)
            view.setInt(R.id.widget_quick_root, "setBackgroundColor", background)
            view.setTextColor(R.id.widget_quick_title, foreground)
            view.setOnClickPendingIntent(R.id.widget_quick_root, actionIntent(app, id, null))
            view.setOnClickPendingIntent(R.id.widget_quick_sos, actionIntent(app, id, ACTION_SOS))
            view.setOnClickPendingIntent(R.id.widget_quick_checkin, actionIntent(app, id, ACTION_CHECKIN))
            manager.updateAppWidget(id, view)
        }
        for (id in widgetIds(app, manager, GuardSosWidgetProvider::class.java)) {
            val view = RemoteViews(app.packageName, R.layout.widget_guard_sos)
            view.setInt(R.id.widget_sos_root, "setBackgroundColor", background)
            view.setTextColor(R.id.widget_sos_title, foreground)
            view.setOnClickPendingIntent(R.id.widget_sos_root, actionIntent(app, id, ACTION_SOS))
            manager.updateAppWidget(id, view)
        }
        for (id in widgetIds(app, manager, GuardCheckinWidgetProvider::class.java)) {
            val view = RemoteViews(app.packageName, R.layout.widget_guard_checkin)
            view.setInt(R.id.widget_checkin_root, "setBackgroundColor", background)
            view.setTextColor(R.id.widget_checkin_title, foreground)
            view.setOnClickPendingIntent(R.id.widget_checkin_root,
                actionIntent(app, id, ACTION_CHECKIN))
            manager.updateAppWidget(id, view)
        }
        for (id in widgetIds(app, manager, GuardPresenceWidgetProvider::class.java)) {
            val view = RemoteViews(app.packageName, R.layout.widget_guard_presence)
            view.setInt(R.id.widget_presence_root, "setBackgroundColor", background)
            view.setTextColor(R.id.widget_presence_title, foreground)
            view.setTextColor(R.id.widget_presence_count, accent)
            view.setTextColor(R.id.widget_presence_time, foreground)
            view.setTextViewText(R.id.widget_presence_count, when {
                !fresh -> "在线状态待确认"
                count == 0 -> "当前无验签在线家人"
                else -> "最近验签在线 $count 台"
            })
            view.setTextViewText(R.id.widget_presence_time, timeLabel)
            view.setOnClickPendingIntent(R.id.widget_presence_root, actionIntent(app, id, null))
            manager.updateAppWidget(id, view)
        }
    }

    private fun actionIntent(context: Context, widgetId: Int, action: String?): PendingIntent {
        val requestCode = widgetId * 4 + when (action) {
            ACTION_SOS -> 1
            ACTION_CHECKIN -> 2
            else -> 0
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = action ?: Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
