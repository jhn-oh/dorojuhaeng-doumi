package kr.juhyu.licensenote

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder
import android.os.Binder
import android.util.Log

class TelemetryService : Service() {
    private lateinit var sdk: SdkTelemetry
    inner class LocalBinder : Binder() {
        fun visible(value: Boolean) = sdk.setForegroundLocation(value)
    }
    private val connection = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        val app = application as LicenseApp
        sdk = SdkTelemetry(this, app::accept) {
            app.coordinator.ui.value.page in setOf(Page.GUIDE, Page.DOORS) && canReview(app.signals.value, android.os.SystemClock.elapsedRealtime())
        }
        sdk.start()
        if (BuildConfig.DEBUG) Log.i("LicenseService", "created; SDK connected to Activity/service lifetime")
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "FOREGROUND_LOCATION") sdk.setForegroundLocation(intent.getBooleanExtra("visible", false))
        if (intent?.action == "SEARCH_DESTINATION") sdk.searchDestination(intent.getStringExtra("query").orEmpty())
        if (intent?.action == "CANCEL_SEARCH") sdk.cancelDestinationSearch()
        if (BuildConfig.DEBUG && intent?.action == "PROBE_NAVIGATION") sdk.probeNavigation()
        if (intent?.action == "OPEN_NAVIGATION") sdk.openNavigation(intent.getBooleanExtra("withCourseRoute", false))
        if (BuildConfig.DEBUG && intent?.action == "OPEN_NAVIGATION_SETTINGS") sdk.openNavigationSettings()
        if (intent?.action == "RECORD") {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("practice", "운전 연습 기록", NotificationManager.IMPORTANCE_LOW))
            try {
                startForeground(1, Notification.Builder(this, "practice").setContentTitle("도로주행도우미").setContentText("선택한 연습의 차량 기록을 남기고 있어요.").setSmallIcon(android.R.drawable.ic_menu_mylocation).build())
                sdk.setRecording(true)
                if (intent.getBooleanExtra("prepareCourseRoute", false)) {
                    val planned = intent.getStringExtra("plannedRoute")?.let { runCatching { parsePlannedRoute(org.json.JSONObject(it)) }.getOrNull() }
                    (planned ?: CourseCatalog.find(intent.getStringExtra("courseId")))?.let { sdk.openNavigation(true, it) }
                }
            } catch (error: SecurityException) {
                (application as LicenseApp).coordinator.recordingFailed()
                stopSelf()
            }
        }
        if (intent?.action == "STOP_RECORD") { sdk.setRecording(false); stopForeground(STOP_FOREGROUND_REMOVE) }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        // A Pleos task can be reparented while its Activity is still alive. Its binding keeps SDK input alive.
        if ((application as LicenseApp).coordinator.ui.value.session?.phase != SessionPhase.RECORDING) stopSelf()
        super.onTaskRemoved(rootIntent)
    }
    override fun onBind(intent: Intent?): IBinder { sdk.setForegroundLocation(true); return connection }
    override fun onUnbind(intent: Intent?): Boolean { sdk.setForegroundLocation(false); return true }
    override fun onRebind(intent: Intent?) { sdk.setForegroundLocation(true); super.onRebind(intent) }
    override fun onDestroy() {
        if (BuildConfig.DEBUG) Log.i("LicenseService", "destroyed; releasing SDK subscriptions")
        sdk.close(); super.onDestroy()
    }
}
