package kr.juhyu.licensenote

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONArray
import org.json.JSONObject

private data class VoiceControls(val client: PleosSpeech, val feedback: String, val busy: Boolean)

@Composable private fun voiceControls(): VoiceControls {
    val context = LocalContext.current.applicationContext
    var feedback by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val client = remember(context) { PleosSpeech(context) { message, active -> feedback = message; busy = active } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(client, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) client.stop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); client.stop() }
    }
    return VoiceControls(client, feedback, busy)
}

@Composable private fun VoiceButton(label: String, onClick: () -> Unit) {
    val palette = LocalPalette.current
    FilledTonalButton(onClick, Modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.tint, contentColor = palette.blue)) {
        Copy(label, 26, palette.blue)
    }
}

@Composable internal fun SpeechInputButton(label: String, onTranscript: (String) -> Unit, modifier: Modifier = Modifier) {
    val voice = voiceControls()
    val deliver by rememberUpdatedState(onTranscript)
    Column(modifier) {
        VoiceButton(if (voice.busy) "듣기 취소" else label) {
            if (voice.busy) voice.client.stop() else voice.client.listen { deliver(it) }
        }
        if (voice.feedback.isNotBlank()) Copy(voice.feedback, 22, LocalPalette.current.second)
    }
}

private const val unmatched = "이 질문은 설명서에서 확인하기 어려워요. 안전하게 주차한 뒤 차량 설명서를 확인하세요."
private fun questionHistory(context: Context, sessionId: String): JSONArray {
    val raw = context.getSharedPreferences("drive-help", Context.MODE_PRIVATE).getString(sessionId, null)
    return runCatching { JSONArray(raw ?: "[]") }.getOrElse { JSONArray() }
}

private fun saveQuestion(context: Context, sessionId: String, question: String, answer: HandbookAnswer?) {
    val saved = questionHistory(context, sessionId)
    if (saved.length() >= 25) saved.remove(0)
    saved.put(JSONObject().put("question", question).put("answer", answer?.text ?: unmatched).put("topic", answer?.topic?.name ?: ""))
    context.getSharedPreferences("drive-help", Context.MODE_PRIVATE).edit().putString(sessionId, saved.toString()).apply()
}

@Composable internal fun PastDriveQuestions(sessionId: String) {
    val context = LocalContext.current
    val history = remember(sessionId) { questionHistory(context, sessionId) }
    if (history.length() == 0) return
    var open by rememberSaveable(sessionId) { mutableStateOf(false) }
    VoiceButton(if (open) "지난 질문 닫기" else "지난 질문 보기 · ${history.length()}개") { open = !open }
    if (open) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for (i in history.length() - 1 downTo 0) {
            val item = history.optJSONObject(i) ?: continue
            Copy(item.optString("question"), 27, LocalPalette.current.navy)
            Copy(item.optString("answer"), 24, LocalPalette.current.second)
            GuideTopic.entries.find { it.name == item.optString("topic") }?.let { topic ->
                var replays by remember(topic, i) { mutableIntStateOf(0) }
                TextButton(onClick = { replays++ }) { Copy("그림으로 보기 · ${topic.title}", 21, LocalPalette.current.blue) }
                if (replays > 0) {
                    val progress = remember(topic) { Animatable(0f) }
                    LaunchedEffect(replays) { progress.snapTo(0f); progress.animateTo(1f, tween(1800)) }
                    Copy("학습용 예시", 20, LocalPalette.current.muted)
                    GuideDrawing(topic, 1, { progress.value }, false, Modifier.fillMaxWidth().height(154.dp))
                }
            }
        }
    }
}

@Composable internal fun DriveHelp(session: PracticeSession, parked: Boolean, signals: Map<String, Signal>, now: Long,
                                   drivingActions: @Composable RowScope.() -> Unit) {
    val context = LocalContext.current
    val voice = voiceControls()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var shown by rememberSaveable(session.id) { mutableStateOf("") }
    var readingAnswer by remember(session.id) { mutableStateOf(false) }
    var transcript by remember(session.id) { mutableStateOf("") }
    var voiceOpen by remember(session.id) { mutableStateOf(false) }
    val sessionId = session.id
    val ux = signals["ux"]
    val canUseVoice = ux?.quality == "VALID" && ux.value?.substringAfter(',')?.toIntOrNull()?.let {
        it and android.car.drivingstate.CarUxRestrictions.UX_RESTRICTIONS_NO_VOICE_TRANSCRIPTION == 0
    } == true
    LaunchedEffect(canUseVoice) { if (!canUseVoice) { voice.client.stop(); voiceOpen = false } }
    LaunchedEffect(voice.busy) { if (!voice.busy) readingAnswer = false }
    fun answer(text: String) {
        val found = GuideAnswers.find(text)
        saveQuestion(context, sessionId, text, found)
        shown = found?.text ?: unmatched
        readingAnswer = true
        voice.client.speak(shown, SystemClock.elapsedRealtime() + 4_000)
    }
    fun listen() {
        transcript = ""
        shown = ""
        voiceOpen = true
        voice.client.listen(onPartial = { transcript = it }) { input ->
            transcript = input
            answer(input)
        }
    }
    val active = session.turns.lastOrNull()?.takeIf { it.exitMs == null && now - it.entryMs in 0L..3000L }
    val turn = signals["turn"]?.takeIf { it.fresh(now) }?.value
    val coaching = if (active == null || turn == null) null else when (turn) {
        active.direction.signal -> "${active.direction.label} 깜빡이 신호가 들어왔어요."
        "OFF" -> "방향을 바꾸기 전에 깜빡이를 확인하세요."
        else -> null
    }
    // Never queue old coaching behind a question, native speech or a pause.
    var lastCue by rememberSaveable(session.id) { mutableStateOf("") }
    LaunchedEffect(active?.id, coaching) {
        if (active != null && coaching != null && lastCue != active.id && !parked) {
            lastCue = active.id
            if (canUseVoice && !voice.client.busy && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                shown = coaching
                voice.client.speak(coaching, active.entryMs + 3000L)
            }
        }
    }
    SpokenDriveCue(session, parked, signals, now, canUseVoice, voice.client)
    val p = LocalPalette.current
    val stop = voice.busy && (voice.client.listening || readingAnswer)
    val feedback = when {
        !canUseVoice -> "차량 음성 입력 제한"
        readingAnswer && parked && shown.isNotBlank() -> shown
        voice.feedback.isNotBlank() -> voice.feedback
        parked || shown == coaching || shown.length <= 24 -> shown
        else -> "지난 질문은 주차 후 확인하세요."
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (feedback.isNotEmpty() && !voiceOpen) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (feedback.isNotEmpty()) Copy(feedback, 23, p.second, modifier = Modifier.semantics {
                        liveRegion = LiveRegionMode.Polite
                    })
                }
            }
        }
        // This space stays reserved when parking or changing the app window width.
        val fabWidth = 82.dp
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomEnd) {
            Row(Modifier.fillMaxWidth().padding(end = fabWidth + 18.dp).heightIn(min = 82.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically, content = drivingActions)
            Surface(
                onClick = {
                    if (stop) {
                        voice.client.stop()
                        readingAnswer = false
                        shown = ""
                        voiceOpen = false
                    } else {
                        listen()
                    }
                },
                enabled = canUseVoice,
                shape = CircleShape,
                color = if (canUseVoice) p.blue else p.light,
                contentColor = if (canUseVoice) p.onAction else p.muted,
                modifier = Modifier.size(fabWidth).testTag("drive-help-fab").semantics {
                    role = Role.Button
                    contentDescription = if (stop) "음성 중지" else "음성으로 질문하기"
                }
                    .shadow(if (canUseVoice) 8.dp else 0.dp, CircleShape)
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(painterResource(if (stop) R.drawable.voice_stop else R.drawable.voice_mic),
                        contentDescription = null, modifier = Modifier.size(34.dp))
                }
            }
        }
    }
    if (voiceOpen && canUseVoice) {
        fun close() { voiceOpen = false; voice.client.stop(); readingAnswer = false }
        Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.fillMaxWidth(.92f).fillMaxHeight(.88f).testTag("voice-transcript-overlay"),
                shape = RoundedCornerShape(28.dp), color = p.paper) {
                Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Copy(if (voice.client.listening) "듣고 있어요" else if (readingAnswer) "답변 중" else "음성 질문",
                        24, p.second)
                    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Copy(transcript.ifBlank { "궁금한 내용을 말해 주세요" }, 44,
                            if (transcript.isBlank()) p.muted else p.ink, FontWeight.SemiBold, TextAlign.Center,
                            modifier = Modifier.testTag("voice-live-transcript").semantics { liveRegion = LiveRegionMode.Polite })
                        if (!voice.client.listening && shown.isNotBlank()) {
                            Spacer(Modifier.height(24.dp))
                            Copy(shown, 26, p.second, align = TextAlign.Center)
                        }
                    }
                    if (!voice.client.listening && !readingAnswer && voice.feedback.isNotBlank())
                        Copy(voice.feedback, 23, p.second, align = TextAlign.Center)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextButton(onClick = ::close) { Copy("닫기", 24, p.second) }
                        if (!voice.busy) VoiceButton("다시 말하기", ::listen)
                        else VoiceButton("음성 중지", ::close)
                    }
                }
            }
        }
    }
}
