package kr.juhyu.licensenote

import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private var appliedFontScale = 1f
    private var fontFocusRecoveryUntil = 0L
    private var fontFocusStartedAt = 0L
    private var fontFocusTaskId = -1
    private var focusRestoreAttempts = 0
    private var pendingFocusRestore: Runnable? = null
    private var telemetryBound = false
    private val telemetryConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) = Unit
        override fun onServiceDisconnected(name: ComponentName) = Unit
    }
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { startTelemetry() }
    private fun startTelemetry() {
        if (!telemetryBound) telemetryBound = bindService(Intent(this, TelemetryService::class.java), telemetryConnection, Context.BIND_AUTO_CREATE)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appliedFontScale = resources.configuration.fontScale
        logConfiguration("created", resources.configuration)
        val requested = mutableListOf("pleos.car.permission.FUSED_LOCATION", "android.car.permission.CAR_SPEED", Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
        val needed = requested.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isEmpty()) startTelemetry() else permissions.launch(needed.toTypedArray())
        val app = application as LicenseApp
        setContent {
            val signals by app.signals.collectAsStateWithLifecycle()
            val ui by app.coordinator.ui.collectAsStateWithLifecycle()
            BackHandler(enabled = ui.page != Page.WELCOME) {
                if (ui.page == Page.RESULT || (ui.page == Page.DRIVING && ui.session?.phase == SessionPhase.RECORDING)) moveTaskToBack(true)
                else app.coordinator.back()
            }
            LicenseNoteUi(ui, signals, app.coordinator)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val focusedBeforeConfig = hasWindowFocus()
        val fontChanged = abs(appliedFontScale - newConfig.fontScale) > .001f
        appliedFontScale = newConfig.fontScale
        super.onConfigurationChanged(newConfig)
        // ComponentActivity forwards the new configuration to Compose; keep the hosted task and screen state.
        logConfiguration("changed", newConfig)
        val decor = window.decorView
        val redraw = Runnable {
            if (!isFinishing && !isDestroyed) {
                decor.requestLayout()
                decor.postInvalidateOnAnimation()
                logConfiguration("redraw", resources.configuration)
            }
        }
        // Pleos can recompose its surrounding task surfaces after the app's configuration callback.
        decor.post(redraw)
        decor.postDelayed(redraw, 1500)
        if (fontChanged) {
            cancelFontFocusRecovery("new_font_configuration")
            if (focusedBeforeConfig) {
                fontFocusRecoveryUntil = SystemClock.uptimeMillis() + 12000
                fontFocusStartedAt = SystemClock.elapsedRealtime()
                fontFocusTaskId = taskId
                focusRestoreAttempts = 0
                if (BuildConfig.DEBUG) Log.i("LicenseUi", "font_focussed activity=${System.identityHashCode(this)} task=$taskId fontScale=${newConfig.fontScale}")
                scheduleFontFocusRecovery()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (BuildConfig.DEBUG) Log.i("LicenseUi", "window_focus activity=${System.identityHashCode(this)} task=$taskId focused=$hasFocus fontRecoveryArmed=${SystemClock.uptimeMillis() < fontFocusRecoveryUntil}")
        if (!hasFocus) scheduleFontFocusRecovery()
    }

    private fun scheduleFontFocusRecovery() {
        if (SystemClock.uptimeMillis() >= fontFocusRecoveryUntil || focusRestoreAttempts >= 3) return
        pendingFocusRestore?.let(window.decorView::removeCallbacks)
        val restore = Runnable {
            pendingFocusRestore = null
            if (SystemClock.uptimeMillis() >= fontFocusRecoveryUntil || focusRestoreAttempts >= 3 || hasWindowFocus() ||
                isFinishing || isDestroyed || taskId != fontFocusTaskId ||
                !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) || window.decorView.windowVisibility != View.VISIBLE) return@Runnable
            val signals = (application as LicenseApp).signals.value
            if (listOf("navigation_request", "navigation_screen", "navigation_launch").any { key ->
                    signals[key]?.let { it.origin == "REQUEST" && it.receivedMs >= fontFocusStartedAt } == true
                }) {
                cancelFontFocusRecovery("navigation_requested")
                return@Runnable
            }
            focusRestoreAttempts += 1
            // A font change restarts Pleos DrivingView and can place its host above this still-visible task.
            // Restore only the previously focused, existing app task; never create or restart an activity.
            runCatching {
                val manager = getSystemService(ActivityManager::class.java)
                val task = manager.appTasks.firstOrNull {
                    val info = it.taskInfo
                    (if (Build.VERSION.SDK_INT >= 29) info.taskId else info.id) == fontFocusTaskId
                }
                checkNotNull(task) { "Existing app task is no longer available" }
                // Move this task directly; AppTask.moveToFront goes through recents and also reorders Home.
                manager.moveTaskToFront(fontFocusTaskId, 0)
                if (BuildConfig.DEBUG) Log.i("LicenseUi", "focus_restore activity=${System.identityHashCode(this)} task=$taskId attempt=$focusRestoreAttempts fontScale=${resources.configuration.fontScale}")
            }.onFailure { Log.w("LicenseUi", "Existing task focus restoration failed", it) }
        }
        pendingFocusRestore = restore
        window.decorView.postDelayed(restore, 1500)
    }

    private fun cancelFontFocusRecovery(reason: String) {
        pendingFocusRestore?.let(window.decorView::removeCallbacks)
        pendingFocusRestore = null
        if (fontFocusRecoveryUntil != 0L && BuildConfig.DEBUG) Log.i("LicenseUi", "focus_restore_cancel activity=${System.identityHashCode(this)} reason=$reason")
        fontFocusRecoveryUntil = 0
    }

    override fun onUserInteraction() {
        cancelFontFocusRecovery("user_interaction")
        super.onUserInteraction()
    }

    override fun onUserLeaveHint() {
        cancelFontFocusRecovery("user_leave")
        super.onUserLeaveHint()
    }

    override fun onStop() {
        cancelFontFocusRecovery("stopped")
        super.onStop()
    }

    override fun onDestroy() {
        cancelFontFocusRecovery("destroyed")
        if (telemetryBound) { unbindService(telemetryConnection); telemetryBound = false }
        super.onDestroy()
    }

    private fun logConfiguration(event: String, config: Configuration) {
        if (BuildConfig.DEBUG) Log.i("LicenseUi", "$event activity=${System.identityHashCode(this)} fontScale=${config.fontScale} night=${config.uiMode and Configuration.UI_MODE_NIGHT_MASK} widthDp=${config.screenWidthDp} heightDp=${config.screenHeightDp}")
    }
}
