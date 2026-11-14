package kr.juhyu.licensenote

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInit
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInit
import java.util.Locale

/** One foreground interaction at a time. A late callback belongs only to its original request. */
internal class PleosSpeech(private val context: Context, private val status: (String, Boolean) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var epoch = 0L
    private var release: (() -> Unit)? = null
    private var focus: AudioFocusRequest? = null
    private var timeout: Runnable? = null
    val busy get() = release != null

    private fun event(id: Long, action: () -> Unit) {
        main.post { if (epoch == id) runCatching(action).onFailure { fail("음성 서비스에 연결할 수 없어요.", it) } }
    }

    private fun fail(label: String, reason: Any?) {
        Log.w("DriveSpeech", "$label ${reason?.let { if (it is Exception) it.javaClass.simpleName else it } ?: ""}")
        stop(label)
    }

    fun stop(label: String = "") {
        epoch++
        timeout?.let(main::removeCallbacks)
        timeout = null
        val cleanup = release
        release = null
        runCatching { cleanup?.invoke() }
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }
        focus = null
        status(label, false)
    }

    private fun start(maxMs: Long): Long? {
        stop()
        val id = epoch
        val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change < 0) event(id) { stop("다른 음성 안내 중이라 멈췄어요.") }
            }, main).build()
        if (audio.mode != AudioManager.MODE_NORMAL || runCatching { audio.requestAudioFocus(request) }.getOrNull() != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            stop("지금은 다른 음성이 사용 중이에요.")
            return null
        }
        focus = request
        timeout = Runnable { if (epoch == id) stop("음성 응답 시간이 지났어요. 다시 시도하세요.") }
            .also { main.postDelayed(it, maxMs) }
        return id
    }

    fun listen(onFinal: (String) -> Unit) {
        val id = start(15_000) ?: return
        status("차량 마이크 연결 중", true)
        runCatching {
            val client = SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
            val listener = object : ResultListener {
                override fun onReady() = Unit
                override fun onStartedRecognition() = event(id) {
                    Log.i("DriveSpeech", "STT listening")
                    status("듣고 있어요", true)
                }
                override fun onUpdated(stt: String, completed: Boolean) {
                    if (completed) event(id) {
                        val text = stt.trim().take(300)
                        Log.i("DriveSpeech", "STT final nonempty=${text.isNotEmpty()}")
                        stop(if (text.isEmpty()) "목소리를 듣지 못했어요." else "")
                        if (text.isNotEmpty()) onFinal(text)
                    }
                }
                override fun onUpdatedEpdData(on: Long, off: Long) = Unit
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onEndedRecognition() = event(id) {
                    // The final text may arrive just after the SDK's end-of-speech event.
                    main.postDelayed({ if (epoch == id) stop("목소리를 듣지 못했어요.") }, 350)
                }
                override fun onError(errorType: SttError) = event(id) { fail("음성 인식을 사용할 수 없어요.", errorType) }
            }
            release = {
                runCatching { client.removeListener(listener) }
                runCatching { client.stop() }
                client.release()
            }
            client.initialize(object : SttInit {
                override fun onFailure(errorType: SttError) = event(id) { fail("차량 음성 인식 연결 실패", errorType) }
                override fun onSuccess() = event(id) {
                    client.addListener(listener)
                    client.setLocale(Locale.KOREA)
                    client.request()
                }
            })
        }.onFailure { fail("차량 음성 인식 연결 실패", it) }
    }

    fun speak(text: String, validUntilMs: Long) {
        if (SystemClock.elapsedRealtime() >= validUntilMs) return
        val id = start(12_000) ?: return
        status("음성 안내 준비 중", true)
        runCatching {
            val client = TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
            val listener = object : EventListener {
                override fun onReady() = Unit
                override fun onStart() = event(id) {
                    if (SystemClock.elapsedRealtime() > validUntilMs) stop()
                    else {
                        Log.i("DriveSpeech", "TTS playback started")
                        status("음성 안내 중", true)
                    }
                }
                override fun onUpdatedRms(rms: Double) = Unit
                override fun onDone() = event(id) { Log.i("DriveSpeech", "TTS playback completed"); stop() }
                override fun onStop() = event(id) { stop() }
                override fun onError(errorType: TtsError) = event(id) { fail("음성 재생을 사용할 수 없어요.", errorType) }
            }
            release = {
                runCatching { client.removeEventListener(listener) }
                runCatching { client.stop() }
                client.release()
            }
            client.initialize(object : TtsInit {
                override fun onFailure(errorType: TtsError) = event(id) { fail("차량 음성 출력 연결 실패", errorType) }
                override fun onSuccess() = event(id) {
                    if (SystemClock.elapsedRealtime() > validUntilMs) stop()
                    else {
                        client.addEventListener(listener)
                        client.setLocale(Locale.KOREA)
                        client.speak(text)
                    }
                }
            })
        }.onFailure { fail("차량 음성 출력 연결 실패", it) }
    }
}
