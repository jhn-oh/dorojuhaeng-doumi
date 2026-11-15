package kr.juhyu.licensenote

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import android.util.AtomicFile

class LicenseApp : Application() {
    @Volatile internal var videoBelt: Boolean? = null
    private val state = MutableStateFlow<Map<String, Signal>>(emptyMap())
    private var currentSignals = emptyMap<String, Signal>()
    val signals = state.asStateFlow()
    private val evidenceIo = Executors.newSingleThreadExecutor()
    private var snapshotAt = 0L
    lateinit var coordinator: PracticeCoordinator
        private set
    override fun onCreate() {
        super.onCreate()
        coordinator = PracticeCoordinator(this, { currentSignals }, { state.value = currentSignals })
    }
    fun accept(key: String, input: Signal) {
        coordinator.dispatch {
            val value = if (BuildConfig.VIDEO_CAPTURE && android.os.Build.HARDWARE in setOf("ranchu", "goldfish") && key == "belt" && videoBelt != null)
                input.copy(value = videoBelt.toString(), quality = "VALID", source = "Emulator/Seat", origin = "INJECTED") else input
            val previous = currentSignals[key]
            currentSignals = currentSignals + (key to value)
            if (previous?.value != value.value || previous?.quality != value.quality || previous?.error != value.error) state.value = currentSignals
            coordinator.onSignal(key, value, previous)
            if (BuildConfig.DEBUG) {
                val json = JSONObject().put("key", key).put("value", value.value ?: JSONObject.NULL)
                    .put("quality", value.quality).put("source", value.source).put("origin", value.origin)
                    .put("receivedMs", value.receivedMs).put("wallTimeMs", System.currentTimeMillis()).put("error", value.error ?: JSONObject.NULL)
                Log.i("LicenseTelemetry", json.toString())
                val snapshot = if (value.receivedMs - snapshotAt > 450) JSONObject().also { obj ->
                    snapshotAt = value.receivedMs
                    currentSignals.forEach { (k, s) -> obj.put(k, JSONObject().put("value", s.value ?: JSONObject.NULL).put("quality", s.quality).put("source", s.source).put("origin", s.origin).put("receivedMs", s.receivedMs).put("error", s.error ?: JSONObject.NULL)) }
                } else null
                evidenceIo.execute {
                    runCatching {
                        val file = File(filesDir, "sdk-evidence.jsonl")
                        if (file.length() > 2_000_000) file.writeText("")
                        file.appendText(json.toString() + "\n")
                        if (snapshot != null) {
                            val atomic = AtomicFile(File(filesDir, "sdk-state.json"))
                            val stream = atomic.startWrite()
                            try { stream.write(snapshot.toString(2).toByteArray()); atomic.finishWrite(stream) }
                            catch (error: Exception) { atomic.failWrite(stream); throw error }
                        }
                    }.onFailure { Log.w("LicenseTelemetry", "Evidence write unavailable", it) }
                }
            }
        }
    }
}
