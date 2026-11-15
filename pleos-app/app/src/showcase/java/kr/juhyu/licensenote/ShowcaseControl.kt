package kr.juhyu.licensenote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock

/** Emulator-only fixture compiled exclusively into the private video capture APK. */
class ShowcaseControl : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!BuildConfig.VIDEO_CAPTURE || Build.HARDWARE !in setOf("ranchu", "goldfish") || !intent.hasExtra("fastened")) return
        val app = context.applicationContext as LicenseApp
        app.videoBelt = intent.getBooleanExtra("fastened", false)
        app.accept("belt", Signal(value = app.videoBelt.toString(), quality = "VALID", source = "Emulator/Seat",
            origin = "INJECTED", receivedMs = SystemClock.elapsedRealtime()))
        if (intent.getBooleanExtra("copyQuestion", false)) {
            val id = app.coordinator.ui.value.session?.id ?: return
            val history = context.getSharedPreferences("drive-help", Context.MODE_PRIVATE)
            val previous = history.all.entries.firstOrNull { it.key != id && it.value is String && (it.value as String).startsWith("[{") }?.value as? String
            if (previous != null) history.edit().putString(id, previous).apply()
        }
    }
}
