package io.github.tigaotg.openkinguard

import android.annotation.SuppressLint
import android.content.Context

/** Single LAN socket/NSD owner shared by the Activity and the optional foreground service. */
internal object S1Runtime {
    @Volatile private var backgroundListening = false
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
        if (!backgroundListening) managerInstance?.stop()
    }

    @Synchronized fun attachService(context: Context) {
        manager(context).start { uiOnChange?.invoke() }
        backgroundListening = true
    }

    @Synchronized fun detachService() {
        backgroundListening = false
        if (!visible) managerInstance?.stop()
    }

    fun isBackgroundListening(): Boolean = backgroundListening
    fun isUiVisible(): Boolean = visible
}
