package kr.juhyu.licensenote

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun GuideScreen(topic: GuideTopic?, signals: Map<String, Signal>, now: Long, wide: Boolean,
                         returnPage: Page, close: () -> Unit, open: (GuideTopic) -> Unit) {
    var section by rememberSaveable { mutableStateOf(GuideSection.START) }
    val listScroll = rememberScrollState()
    val returnButton: @Composable (Boolean) -> Unit = { primary ->
        GuideButton(returnPage.guideReturnLabel(), Modifier.fillMaxWidth().padding(top = 12.dp), primary = primary, onClick = close)
    }
    if (topic == null) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GuideDashboard(signals, now, wide, section, listScroll, { section = it }, open)
            }
            returnButton(true)
        }
    } else key(topic) {
        GuideLesson(topic, signals, now, wide, returnButton)
    }
}

@Composable
private fun GuideDashboard(signals: Map<String, Signal>, now: Long, wide: Boolean, section: GuideSection,
                           listScroll: androidx.compose.foundation.ScrollState, select: (GuideSection) -> Unit, open: (GuideTopic) -> Unit) {
    val p = LocalPalette.current
    val topics: @Composable ColumnScope.() -> Unit = {
        val cards = GuideTopic.entries.filter { it.section == section }
        cards.chunked(if (wide) 2 else 1).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEach { item ->
                    Box(Modifier.weight(1f)) { GuideCard(item, item.subtitle, featured = item == GuideTopic.SPEED, open = open) }
                }
                if (wide && pair.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    val warnings: @Composable ColumnScope.() -> Unit = {
        Copy("내 차의 경고", 31, weight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(16.dp))
        val beltState = guideWarning(signals[GUIDE_BELT_WARNING], now)
        WarningCard(GuideTopic.BELT, "운전석 안전벨트", beltState, open,
            buckle = guideBuckleLabel(signals["belt"], now).takeIf { beltState == GuideWarning.UNSUPPORTED || beltState == GuideWarning.UNKNOWN })
        Spacer(Modifier.height(10.dp))
        WarningCard(GuideTopic.WASHER, "워셔액 부족", guideWarning(signals[GUIDE_WASHER_WARNING], now), open)
    }
    Column(Modifier.fillMaxSize().verticalScroll(listScroll)) {
        Copy("내 차 익히기", if (wide) 58 else 44, p.ink, FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(18.dp))
        GuideSection.entries.chunked(if (wide) 4 else 2).forEach { items ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items.forEach { item ->
                    GuideButton(item.label, Modifier.weight(1f).semantics { selected = section == item }, primary = section == item) { select(item) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(18.dp))
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            Column(Modifier.weight(1.4f), content = topics)
            Column(Modifier.weight(1f), content = warnings)
        } else {
            topics()
            Spacer(Modifier.height(26.dp))
            warnings()
        }
    }
}

@Composable
private fun GuideCard(topic: GuideTopic, subtitle: String, featured: Boolean = false, open: (GuideTopic) -> Unit) {
    val p = LocalPalette.current
    val ink = if (featured) p.onAction else p.ink
    Surface(onClick = { open(topic) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (featured) p.navy else p.light) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 18.dp)) {
            GuideGlyph(topic, if (featured) ink else p.blue, Modifier.size(38.dp))
            Spacer(Modifier.height(6.dp))
            Copy(topic.title, 28, ink, FontWeight.SemiBold)
            Copy(subtitle, 22, if (featured) ink else p.second)
        }
    }
}

@Composable
private fun WarningCard(topic: GuideTopic, title: String, state: GuideWarning, open: (GuideTopic) -> Unit, buckle: String? = null) {
    val p = LocalPalette.current
    val active = state == GuideWarning.ON
    Surface(onClick = { open(topic) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
        color = if (active) p.tint else p.paper, border = BorderStroke(if (active) 2.dp else 1.dp, if (active) p.blue else p.line)) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            GuideGlyph(topic, if (active) p.blue else p.second, Modifier.size(42.dp))
            Column(Modifier.weight(1f)) {
                Copy(title, 29, p.ink, FontWeight.SemiBold)
                Copy(if (state == GuideWarning.UNSUPPORTED) "경고 신호 미지원" else state.label, 23, if (active) p.blue else p.second)
                if (buckle != null) Copy(buckle, 23, p.navy, FontWeight.Medium)
            }
            Copy("보기", 23, p.blue, FontWeight.Medium)
        }
    }
}

@Composable
private fun GuideLesson(topic: GuideTopic, signals: Map<String, Signal>, now: Long, wide: Boolean, returnButton: @Composable (Boolean) -> Unit) {
    val p = LocalPalette.current
    var step by rememberSaveable { mutableIntStateOf(0) }
    var alternate by rememberSaveable { mutableStateOf(false) }
    var questionOpen by rememberSaveable { mutableStateOf(false) }
    var answer by rememberSaveable { mutableIntStateOf(-1) }
    var replay by remember { mutableIntStateOf(0) }
    val motion = remember { Animatable(0f) }
    LaunchedEffect(step, alternate, replay) {
        motion.snapTo(0f)
        motion.animateTo(1f, tween(1800, easing = LinearEasing))
    }
    val steps = remember(topic, alternate) { topic.steps(alternate) }
    val source = when (topic) {
        GuideTopic.BELT -> "내 차 · 운전석 경고 · " + guideWarning(signals[GUIDE_BELT_WARNING], now).label +
            "\n운전석 버클 · " + guideBuckleLabel(signals["belt"], now)
        GuideTopic.WASHER -> "내 차 · 워셔액 부족 · " + guideWarning(signals[GUIDE_WASHER_WARNING], now).label
        GuideTopic.WIPER -> wiperLabel(signals["wiper_front"], now)
        GuideTopic.DOOR -> "내 차 · " + guideDoorSummary(signals, now)
        GuideTopic.BEAMS -> "내 차 · 하향등 " + guideBeamLabel(signals[GUIDE_LOW_BEAM], now) +
            " · 상향등 " + guideBeamLabel(signals[GUIDE_HIGH_BEAM], now)
        GuideTopic.SIGNAL -> "내 차 · 방향지시등 · " + when (signals["turn"].takeIf { it.fresh(now) }?.value) {
            "LEFT" -> "왼쪽 켜짐"; "RIGHT" -> "오른쪽 켜짐"; "OFF" -> "꺼짐"; else -> "신호 확인 불가"
        }
        else -> null
    }
    val picture: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().background(p.tint, RoundedCornerShape(24.dp)).padding(24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Copy("학습용 예시", 23, p.navy, FontWeight.SemiBold)
                if (topic == GuideTopic.SPEED) Copy("D", 25, p.navy, FontWeight.SemiBold)
            }
            GuideDrawing(topic, step, { motion.value }, alternate, Modifier.fillMaxWidth().height(if (wide) 242.dp else 156.dp))
            if (topic == GuideTopic.SPEED) {
                val speed by remember(step) { derivedStateOf { (guideSpeedFraction(step, motion.value) * 40).roundToInt() } }
                Copy("${speed} km/h", 37, p.navy, FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
            }
            val caption = when (topic) {
                GuideTopic.HOLD -> listOf("AUTO HOLD · 흰색 대기", "AUTO HOLD · 초록색 정차 유지", "AUTO HOLD · 노란색 점검")[step]
                GuideTopic.DOOR -> if (step == 2) "앞쪽 ↑  ·  좌석 문 닫힘" else "앞쪽 ↑"
                GuideTopic.TIRE -> if (step == 1 && alternate) "약 1분간 점멸 → 점등 (시간을 줄인 예시)" else null
                GuideTopic.PARKING_BRAKE -> listOf("브레이크 페달 · 정차", "EPB 스위치 · 위로 당김", "P단 · 주차 브레이크 체결")[step]
                else -> null
            }
            if (caption != null) Copy(caption, 22, p.navy, FontWeight.Medium, modifier = Modifier.align(Alignment.CenterHorizontally))
        }
    }
    val explanation: @Composable ColumnScope.() -> Unit = {
        topic.examples?.let { options ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf(false to options.first, true to options.second).forEach { (other, label) ->
                    GuideButton(label, primary = other == alternate, modifier = Modifier.semantics { selected = other == alternate }) {
                        alternate = other; step = 1; answer = -1; replay++
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
        Copy("${step + 1} / ${steps.size}", 23, p.blue, FontWeight.SemiBold)
        Copy(steps[step].title, if (wide) 43 else 36, p.ink, FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Copy(steps[step].text, 28, p.second)
        if (topic == GuideTopic.WIPER && step == 1) Copy("접힘 → 위로 쓸기 → 제자리", 23, p.navy, FontWeight.Medium)
        if (source != null) {
            Spacer(Modifier.height(20.dp))
            HorizontalDivider(color = p.line)
            Spacer(Modifier.height(12.dp))
            Copy(source, 23, p.navy, FontWeight.Medium)
        }
        topic.manualSection?.let { section -> Spacer(Modifier.height(12.dp)); Copy("2026 쏘나타 취급설명서 · $section", 20, p.muted) }
    }
    val question = topic.question
    if (questionOpen && question != null) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GuideQuestionView(question, answer, { answer = it }) { questionOpen = false; answer = -1 }
            }
            returnButton(false)
        }
        return
    }
    val lessonScroll = key(step) { rememberScrollState() }
    val lessonComplete = step == steps.lastIndex && question == null
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(lessonScroll)) {
            Copy(topic.title, if (wide) 58 else 44, p.ink, FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            Spacer(Modifier.height(if (wide) 34.dp else 18.dp))
            if (wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                Box(Modifier.weight(1f)) { picture() }
                Column(Modifier.weight(1f), content = explanation)
            } else { picture(); Spacer(Modifier.height(22.dp)); explanation() }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GuideButton("이전", enabled = step > 0, modifier = Modifier.weight(1f)) { step-- }
            GuideButton("다시 보기", modifier = Modifier.weight(1.2f)) { replay++ }
            GuideButton(if (step != steps.lastIndex) "다음 단계" else if (question != null) "상황 문제" else "처음부터",
                primary = !lessonComplete, modifier = Modifier.weight(1.4f)) {
                if (step < steps.lastIndex) step++
                else if (question != null) { answer = -1; questionOpen = true }
                else step = 0
            }
        }
        returnButton(lessonComplete)
    }
}

@Composable
private fun GuideQuestionView(question: GuideQuestion, answer: Int, select: (Int) -> Unit, close: () -> Unit) {
    val p = LocalPalette.current
    androidx.activity.compose.BackHandler(onBack = close)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Copy("상황 문제", 25, p.blue, FontWeight.Medium)
            Copy(question.prompt, 42, weight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            listOf(question.first, question.second).forEachIndexed { index, option ->
                GuideButton(option, Modifier.fillMaxWidth().semantics { selected = index == answer }, primary = index == answer) { select(index) }
            }
            if (answer >= 0) {
                Column(Modifier.fillMaxWidth().background(p.tint, RoundedCornerShape(16.dp)).padding(20.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite }) {
                    Copy(if (answer == question.correct) "맞았어요" else "다시 골라보세요", 30, p.navy, FontWeight.SemiBold)
                    Copy(question.reason, 26, p.ink)
                }
            }
        }
        GuideButton("설명으로 돌아가기", Modifier.fillMaxWidth().padding(top = 12.dp), onClick = close)
    }
}

@Composable
private fun GuideButton(text: String, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(onClick, modifier.heightIn(min = 68.dp), enabled, shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (primary) p.blue else p.light, disabledContainerColor = p.light),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
        Copy(text, 25, if (!enabled) p.muted else if (primary) p.onAction else p.ink, FontWeight.Medium)
    }
}
