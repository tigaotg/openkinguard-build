package io.github.tigaotg.openkinguard

import android.annotation.SuppressLint
import android.content.Context

/** Single LAN socket/NSD owner shared by the Activity and the optional foreground service. */
internal object S1Runtime {
    @Volatile private var backgroundListening = false
    // A projection foreground service may temporarily own the transport even
    // when the person opens another app. This flag is never persisted.
    @Volatile private var assistForegroundListening = false
    @Volatile private var visible = false
    private var uiOnChange: (() -> Unit)? = null
    // The manager stores only context.applicationContext; no Activity/Window is retained.
    @SuppressLint("StaticFieldLeak")
    private var managerInstance: S1GuardManager? = null

    @Synchronized fun manager(context: Context): S1GuardManager =
        managerInstance ?: S1GuardManager(context.applicationContext).also { managerInstance = it }

    @Synchronized fun attachUi(context: Context, onChange: () -> Unit) {
        visible = true
        uiOnChange = onChange
        manager(context).start { uiOnChange?.invoke() }
    }

    @Synchronized fun detachUi() {
        visible = false
        uiOnChange = null
        if (!backgroundListening && !assistForegroundListening) managerInstance?.stop()
    }

    @Synchronized fun attachService(context: Context) {
        manager(context).start { uiOnChange?.invoke() }
        backgroundListening = true
    }

    @Synchronized fun detachService() {
        backgroundListening = false
        if (!visible && !assistForegroundListening) managerInstance?.stop()
    }

    @Synchronized fun attachAssistForeground(context: Context) {
        assistForegroundListening = true
        try {
            manager(context).start { uiOnChange?.invoke() }
        } catch (failure: Exception) {
            assistForegroundListening = false
            throw failure
        }
    }

    @Synchronized fun detachAssistForeground() {
        assistForegroundListening = false
        if (!visible && !backgroundListening) managerInstance?.stop()
    }

    fun isBackgroundListening(): Boolean = backgroundListening
    fun isUiVisible(): Boolean = visible
}
