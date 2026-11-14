package kr.juhyu.licensenote

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import com.caverock.androidsvg.SVG
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalTextApi::class)
private val Asta = FontFamily(
    Font(R.font.asta_sans, FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.asta_sans, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.asta_sans, FontWeight.SemiBold, variationSettings = FontVariation.Settings(FontVariation.weight(600))),
)
internal data class Palette(val paper: Color, val ink: Color, val second: Color, val muted: Color, val light: Color, val line: Color, val blue: Color, val tint: Color, val navy: Color, val onAction: Color)
private val Day = Palette(Color.White, Color(0xFF191F28), Color(0xFF4E5968), Color(0xFF647181), Color(0xFFF2F4F6), Color(0xFFDCE2E8), Color(0xFF185CCB), Color(0xFFEEF4FF), Color(0xFF002C5F), Color.White)
private val Night = Palette(Color(0xFF11171D), Color(0xFFEDF2F8), Color(0xFFC0CDDA), Color(0xFFA6BBCD), Color(0xFF1C2731), Color(0xFF465769), Color(0xFF8DBDFF), Color(0xFF1B304B), Color(0xFFBCD6FF), Color(0xFF0D1B2B))
internal val LocalPalette = staticCompositionLocalOf { Day }
internal val LocalAppTextScale = staticCompositionLocalOf { 1f }

@Composable internal fun Copy(text: String, size: Int = 34, color: Color = LocalPalette.current.ink, weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start, modifier: Modifier = Modifier) {
    val textScale = LocalAppTextScale.current
    Text(text, modifier, color, fontSize = (size * textScale).sp, fontWeight = weight, fontFamily = Asta, lineHeight = (size * 1.36 * textScale).sp, textAlign = align)
}
@Composable private fun Action(text: String, enabled: Boolean = true, secondary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(onClick, modifier.heightIn(min = 92.dp), enabled = enabled, shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = if (secondary) p.light else p.blue, contentColor = if (secondary) p.ink else p.onAction), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp)) {
        Copy(text, 34, if (enabled) if (secondary) p.ink else p.onAction else p.muted, FontWeight.SemiBold, TextAlign.Center)
    }
}
@Composable private fun Link(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 76.dp), enabled = enabled, shape = RoundedCornerShape(17.dp)) { Copy(text, 29, if (enabled) LocalPalette.current.blue else LocalPalette.current.muted, FontWeight.Medium, TextAlign.Center) }
}
@Composable private fun FigmaIcon(id: Int, size: Int, color: Color? = LocalPalette.current.blue, mirrored: Boolean = false) {
    val context = LocalContext.current
    val asset = when (id) {
        R.drawable.figma_back -> "back"
        R.drawable.figma_belt -> "belt"
        R.drawable.figma_door -> "door"
        R.drawable.figma_mirror -> "mirror"
        R.drawable.figma_warning -> "warning"
        else -> "left"
    }
    val painter = remember(asset) {
        val picture = SVG.getFromAsset(context.assets, "figma/$asset.svg").renderToPicture()
        object : Painter() {
            override val intrinsicSize = Size(picture.width.toFloat(), picture.height.toFloat())
            override fun DrawScope.onDraw() {
                drawIntoCanvas { canvas ->
                    canvas.save(); canvas.scale(this.size.width / picture.width, this.size.height / picture.height)
                    canvas.nativeCanvas.drawPicture(picture); canvas.restore()
                }
            }
        }
    }
    Image(painter, null, Modifier.size(size.dp).graphicsLayer { scaleX = if (mirrored) -1f else 1f }, colorFilter = if (id == R.drawable.figma_back || id == R.drawable.figma_left) color?.let { ColorFilter.tint(it) } else null)
}

@Composable fun LicenseNoteUi(input: PracticeUi, signals: Map<String, Signal>, c: PracticeCoordinator) {
    val ui = input.copy(now = android.os.SystemClock.elapsedRealtime())
    val configuration = LocalConfiguration.current
    val baseDensity = LocalDensity.current
    LaunchedEffect(configuration.fontScale, baseDensity.fontScale, configuration.uiMode) {
        if (BuildConfig.DEBUG) android.util.Log.i("LicenseUi", "compose_font configuration=${configuration.fontScale} base=${baseDensity.fontScale} density=${baseDensity.density} base20px=${with(baseDensity) { 20.sp.toPx() }} base24px=${with(baseDensity) { 24.sp.toPx() }} base34px=${with(baseDensity) { 34.sp.toPx() }}")
    }
    LaunchedEffect(ui.textScale) { if (BuildConfig.DEBUG) android.util.Log.i("LicenseUi", "app_text_scale factor=${ui.textScale} system_font_scale=${configuration.fontScale}") }
    val dark = isSystemInDarkTheme()
    val p = if (dark) Night else Day
    CompositionLocalProvider(LocalPalette provides p, LocalAppTextScale provides ui.textScale) {
        MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = p.blue, background = p.paper, surface = p.paper, onSurface = p.ink) else lightColorScheme(primary = p.blue, background = p.paper, surface = p.paper, onSurface = p.ink), typography = Typography(bodyLarge = TextStyle(fontFamily = Asta))) {
            Surface(Modifier.fillMaxSize(), color = p.paper) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= 1100.dp
                    val parked = canReview(signals, ui.now)
                    var video by remember { mutableStateOf<Int?>(null) }
                    var legal by remember { mutableStateOf(false) }
                    LaunchedEffect(parked) { if (!parked) { video = null; legal = false } }
                    Column(Modifier.fillMaxSize().padding(horizontal = if (wide) 92.dp else 56.dp).padding(top = 44.dp, bottom = 34.dp)) {
                        Header(ui, c, parked, onLegal = { legal = true })
                        Spacer(Modifier.height(36.dp))
                        if (ui.loading) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Copy("기록을 불러오고 있어요", 40, p.second) }
                        else if (!parked && ui.page != Page.DRIVING) LockScreen(ui.session?.phase == SessionPhase.RECORDING)
                        else Box(Modifier.weight(1f)) {
                            when (ui.page) {
                                Page.WELCOME -> Preparation(wide, R.drawable.figma_warning, 0, "연습면허로 운전하나요?", "연습운전면허와 주행연습 표지를 준비하세요.\n이 차종 면허를 딴 지 2년이 지났고 면허가 유효한 분에게 지도를 받으세요.", { Action("확인했어요", modifier = Modifier.fillMaxWidth(), onClick = c::acceptIntro); Link("이미 운전면허가 있어요", modifier = Modifier.fillMaxWidth(), onClick = c::acceptLicensed) })
                                Page.BELT -> {
                                    val belt = signals["belt"]
                                    val worn = belt.fresh(ui.now) && belt?.value == "true"
                                    Preparation(wide, R.drawable.figma_belt, 1, if (worn) "안전벨트를 맸어요" else if (belt.fresh(ui.now)) "안전벨트를 매세요" else "안전벨트를 확인해 주세요", if (worn) "운전석 버클이 채워졌어요." else if (belt.fresh(ui.now)) "운전석 버클에 벨트를 끼워 주세요." else "버클 신호가 없어요. 벨트를 맨 뒤 직접 확인해 주세요.", {
                                        if (worn) Action("다음", modifier = Modifier.fillMaxWidth()) { c.confirmBelt(false) }
                                        Link(if (belt.fresh(ui.now)) "직접 확인했어요" else "벨트를 맸어요", modifier = Modifier.fillMaxWidth()) { c.confirmBelt(true) }
                                    })
                                }
                                Page.MIRROR -> Preparation(wide, R.drawable.figma_mirror, 2, "거울을 맞춰 주세요", "좌석을 맞춘 뒤 룸미러와 양쪽 사이드미러가 잘 보이는지 확인하세요.", {
                                    Link("조절 방법 보기", modifier = Modifier.fillMaxWidth()) { video = R.raw.mirror_help }
                                    Action("잘 보여요", modifier = Modifier.fillMaxWidth()) { c.go(Page.DOORS) }
                                })
                                Page.DOORS -> {
                                    val known = signals.filterKeys { it.startsWith("door:") }
                                    val full = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT").all { "door:$it" in known } && known.values.all { it.fresh(ui.now) }
                                    val shut = full && known.values.all { it.value == "0" }
                                    val open = known.entries.filter { it.value.value != "0" && it.value.fresh(ui.now) }.map { doorName(it.key) }
                                    Preparation(wide, R.drawable.figma_door, 3, if (shut) "문을 모두 닫았어요" else if (open.isNotEmpty()) "열린 곳이 있어요 · ${open.first()}" else "문이 모두 닫혔나요?", if (shut) "출발하기 전, 차 앞뒤와 양옆에 사람이나 장애물이 없는지 살펴보세요." else "문을 닫고 차 앞뒤와 양옆을 살펴보세요.", {
                                        Link("와이퍼 사용법", modifier = Modifier.fillMaxWidth()) { video = R.raw.wiper_help }
                                        Action(if (shut) "코스 보기" else "모두 닫혔어요", modifier = Modifier.fillMaxWidth()) { c.confirmDoors(!shut) }
                                    })
                                }
                                Page.COURSE -> CourseScreen(ui, signals, wide, c)
                                Page.DRIVING -> DrivingScreen(ui, signals, wide, parked, c)
                                Page.REVIEW -> ReviewScreen(ui, wide, c)
                                Page.RESULT -> ResultScreen(ui, wide, c)
                                Page.NOTEBOOK -> NotebookScreen(ui, wide, c)
                                Page.LESSON -> LessonScreen(ui, c)
                                Page.DIAGNOSTICS -> Diagnostics(signals, c)
                                Page.GUIDE -> GuideScreen(ui.guideTopic, signals, ui.now, wide, c::openGuideTopic)
                            }
                        }
                        ui.error?.let { error -> Copy(error, 26, p.blue); if (error.startsWith("기록을 저장") && ui.session != null) Link("다시 저장하기", onClick = c::retrySave) }
                        if (ui.saving) Copy("기록을 저장하고 있어요", 26, p.second)
                    }
                    if (video != null && parked) VideoHelp(video!!, onClose = { video = null })
                    if (legal && parked) Dialog(onDismissRequest = { legal = false }) {
                        Surface(shape = RoundedCornerShape(30.dp), color = p.paper) { Column(Modifier.widthIn(max = 1100.dp).padding(48.dp).verticalScroll(rememberScrollState())) {
                            Copy("연습면허와 주행연습 표지", 46, weight = FontWeight.SemiBold)
                            Spacer(Modifier.height(32.dp)); Copy("연습운전면허가 유효한지 확인하세요.\n해당 차종 면허를 받은 지 2년이 지났고 면허가 유효한 분에게 지도를 받으세요.\n주행연습 표지를 붙이고 연습 목적으로 운전하세요. 차량 사용 동의와 보험 보장도 확인하세요.", 32)
                            Spacer(Modifier.height(32.dp)); Action("닫기", modifier = Modifier.fillMaxWidth()) { legal = false }
                        } }
                    }
                }
            }
        }
    }
}

@Composable private fun Header(ui: PracticeUi, c: PracticeCoordinator, parked: Boolean, onLegal: () -> Unit) {
    val reported = ui.session?.safetyIssue == true
    var confirmIssue by remember(ui.session?.id, ui.page, parked) { mutableStateOf(false) }
    var textSizeDialog by remember(ui.page, parked, ui.loading, ui.saving) { mutableStateOf(false) }
    val canChangeText = parked && !ui.loading && !ui.saving
    if (textSizeDialog && canChangeText) Dialog(onDismissRequest = { textSizeDialog = false }) {
        val p = LocalPalette.current
        Surface(shape = RoundedCornerShape(30.dp), color = p.paper) {
            Column(Modifier.widthIn(max = 680.dp).padding(32.dp).verticalScroll(rememberScrollState())) {
                Copy("글자 크기", 42, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp)); Copy("이 앱의 글자만 바꿔요.", 28, p.second)
                Spacer(Modifier.height(24.dp))
                listOf("기본" to 1f, "크게" to 1.3f).forEach { (label, scale) ->
                    val chosen = abs(ui.textScale - scale) < .001f
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Action(label, secondary = !chosen, modifier = Modifier.weight(1f).semantics { selected = chosen }) { c.setTextScale(scale) }
                        Copy(if (chosen) "선택됨" else "", 22, p.blue, align = TextAlign.Center, modifier = Modifier.width(96.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                }
                Link("닫기", modifier = Modifier.fillMaxWidth()) { textSizeDialog = false }
            }
        }
    }
    if (confirmIssue && parked) {
        AlertDialog(
            onDismissRequest = { confirmIssue = false },
            title = { Copy(if (reported) "안전 문제 기록을 취소할까요?" else "이번 연습에 안전 문제가 있었나요?", 36, weight = FontWeight.SemiBold) },
            text = { Copy(if (reported) "저장한 답변과 차량 기록으로 다시 평가해요." else "안전 문제를 기록하면 이번 연습의 평가를 보류해요. 동승자가 남긴 답변은 보관해요.", 28) },
            confirmButton = { Link(if (reported) "기록 취소" else "안전 문제 기록", !ui.saving) { confirmIssue = false; c.setSafetyIssue(!reported) } },
            dismissButton = { Link("돌아가기") { confirmIssue = false } },
        )
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 83.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (ui.page !in setOf(Page.WELCOME, Page.DRIVING, Page.RESULT)) IconButton(onClick = c::back, enabled = parked, modifier = Modifier.size(76.dp).semantics { contentDescription = "뒤로" }) { FigmaIcon(R.drawable.figma_back, 64, LocalPalette.current.second) }
            Copy(when(ui.page) { Page.WELCOME, Page.DRIVING -> "운전 연습"; Page.BELT, Page.MIRROR, Page.DOORS -> "출발 준비"; Page.COURSE -> "경로 미리보기"; Page.REVIEW -> "주차 후 확인"; Page.RESULT -> "연습 결과"; Page.NOTEBOOK -> "오답노트"; Page.LESSON -> if (ui.session?.rulesVersion == 1) "차로 변경 연습" else "회전 연습"; Page.DIAGNOSTICS -> "SDK 연결 확인"; Page.GUIDE -> "운전 설명서" }, 29, LocalPalette.current.second, FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canChangeText && ui.page.hasGuideEntry()) Link("설명서", onClick = c::openGuide)
            if (canChangeText) Link("글자", modifier = Modifier.semantics { contentDescription = "글자 크기: ${if (ui.textScale > 1f) "크게" else "기본"}" }) { textSizeDialog = true }
            when (ui.page) {
                Page.WELCOME -> Link("면허와 표지", parked, onClick = onLegal)
                Page.BELT, Page.MIRROR, Page.DOORS -> Copy("${ui.page.ordinal} / 3", 34, LocalPalette.current.blue, FontWeight.SemiBold)
                Page.REVIEW, Page.RESULT -> Link(if (reported) "안전 문제 수정" else "안전 문제", parked && !ui.saving && ui.session != null) { confirmIssue = true }
                else -> if (ui.page != Page.GUIDE && BuildConfig.DEBUG && parked) Link("SDK", onClick = c::diagnostics)
            }
        }
    }
}

@Composable private fun Preparation(wide: Boolean, icon: Int, step: Int, title: String, body: String, actions: @Composable ColumnScope.() -> Unit) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (step > 0) Row(horizontalArrangement = Arrangement.spacedBy(9.dp), modifier = Modifier.padding(bottom = 60.dp)) { repeat(3) { Box(Modifier.width(41.dp).height(7.dp).background(if (it < step) p.blue else p.line, RoundedCornerShape(4.dp))) } }
            else Spacer(Modifier.height(60.dp))
            FigmaIcon(icon, if (wide) if (icon == R.drawable.figma_warning) 216 else 228 else if (icon == R.drawable.figma_warning) 187 else 195, if (icon == R.drawable.figma_warning) Color(0xFF805619) else p.blue)
            Spacer(Modifier.height(34.dp)); Copy(title, if (wide) 74 else 58, weight = FontWeight.SemiBold, align = TextAlign.Center, modifier = Modifier.widthIn(max = 1080.dp))
            Spacer(Modifier.height(30.dp)); Copy(body, if (wide) 40 else 34, p.second, align = TextAlign.Center, modifier = Modifier.widthIn(max = 1080.dp))
        }
        Column(Modifier.widthIn(max = 784.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), content = actions)
    }
}

@Composable private fun CourseScreen(ui: PracticeUi, signals: Map<String, Signal>, wide: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current
    val targets = ui.session?.takeIf { it.nextPractice && it.rulesVersion >= 2 }?.let(::practiceTargetIds).orEmpty()
    Column(Modifier.fillMaxSize()) {
        val text: @Composable ColumnScope.() -> Unit = {
            Copy(PracticeCourse.name, if (wide) 74 else 58, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            if (targets.isEmpty()) Copy("첫 번째 좌회전, 두 번째 우회전에서 깜빡이 켜기", 34, p.second)
            else EvidenceCard("이번 연습 목표", targets.joinToString("\n") { practiceTargetLabel(it) + " 전 깜빡이 켜기" })
            Spacer(Modifier.height(32.dp))
            Copy("${PracticeCourse.points.zipWithNext().sumOf { (a, b) -> distanceMeters(a, b) }.toInt()} m", 58, p.blue, FontWeight.SemiBold)
            Copy("에뮬레이터용 코스 · 실제 도로 통행 조건 미확인", 26, p.muted)
            Spacer(Modifier.height(20.dp))
            Copy("경유지 두 곳과 목적지를 기본 내비에 자동으로 설정합니다.", 28, p.second)
        }
        if (wide) Row(Modifier.weight(1f).fillMaxWidth().padding(top = 60.dp), horizontalArrangement = Arrangement.spacedBy(70.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = text)
            CourseMap(locationPoint(signals["location"], ui.now), Modifier.weight(1f).height(570.dp))
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { text(); Spacer(Modifier.height(30.dp)); CourseMap(locationPoint(signals["location"], ui.now), Modifier.fillMaxWidth().height(330.dp)) }
        Action("이 코스로 시작", enabled = !ui.saving, modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().align(Alignment.CenterHorizontally), onClick = c::start)
        Spacer(Modifier.height(16.dp)); Copy("내비 안내를 확인한 뒤 출발하세요.", 24, p.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun DrivingScreen(ui: PracticeUi, signals: Map<String, Signal>, wide: Boolean, parked: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current; val s = ui.session
    val navigationRequest = signals["navigation_request"]?.takeIf { s != null && it.receivedMs >= s.startedAtMs }
    val routeReady = navigationRequest?.quality == "VALID" && navigationRequest.value == "ROUTE_READY"
    val routeArrived = navigationRequest?.quality == "VALID" && navigationRequest.value == "ARRIVED"
    val preparingRoute = !routeReady && !routeArrived
    val completed = s?.turns.orEmpty().filter { it.exitMs != null }
    val active = s?.turns?.lastOrNull()?.takeIf { it.exitMs == null && it.headingChangeDeg == null &&
        ui.now - it.entryMs in 0L..30_000L && !parked }
    val title = when {
        routeArrived -> "기본 내비 도착 안내"
        preparingRoute && navigationRequest?.quality == "ERROR" -> "기본 내비를 확인해 주세요"
        preparingRoute -> "출발 경로 준비 중"
        locationPoint(signals["location"], ui.now) == null -> "위치 신호 대기 중"
        !signals["turn"].fresh(ui.now) -> "깜빡이 신호 대기 중"
        !signals["steering"].fresh(ui.now) -> "조향 신호 대기 중"
        active != null -> "${active.direction.label} 구간 기록 중"
        else -> "주행 기록 중"
    }
    val guide: @Composable ColumnScope.() -> Unit = {
        Copy(title, if (wide) 54 else 48, weight = FontWeight.SemiBold, align = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        val elapsed = ((ui.now - (s?.startedAtMs ?: ui.now)) / 1000).coerceAtLeast(0)
        Copy("${elapsed / 60}분 ${elapsed % 60}초", 34, p.second)
        if (preparingRoute) {
            Spacer(Modifier.height(18.dp))
            Copy(if (parked) "내비 안내가 확인될 때까지 P단을 유지해 주세요." else "경로 확인이 끝나지 않았어요. 정차 후 기본 내비를 확인해 주세요.", 28, p.second, align = TextAlign.Center)
        } else if (routeArrived) {
            Spacer(Modifier.height(18.dp))
            Copy(if (parked) "도착 안내를 받았어요. 기록을 마쳐 주세요." else "안전하게 정차한 뒤 기록을 마치세요.", 28, p.second, align = TextAlign.Center)
        }
        if (completed.isNotEmpty()) Copy("주차 후 확인할 회전 ${completed.size}곳", 30, p.second)
        val targets = s?.practiceTargets.orEmpty()
        if (targets.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Copy("이번 목표 · " + targets.joinToString(" · ") { practiceTargetLabel(it) + " 깜빡이" }, 29, p.blue, align = TextAlign.Center)
        }
        Link("기본 내비 열기") { c.openNavigation() }
        NavigationStatus(navigationRequest)
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (wide) Row(Modifier.weight(1f).fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(48.dp), verticalAlignment = Alignment.CenterVertically) {
            CourseMap(locationPoint(signals["location"], ui.now), Modifier.weight(1f).fillMaxHeight(), linksEnabled = parked)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, content = guide)
        } else Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            CourseMap(locationPoint(signals["location"], ui.now), Modifier.fillMaxWidth().height(280.dp), linksEnabled = parked)
            Spacer(Modifier.height(28.dp)); guide()
        }
        if (parked) Action(if (ui.saving) "기록을 저장하고 있어요" else "기록 종료", enabled = !ui.saving, modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(), onClick = c::finish)
        else Copy("주차 후 기록을 마치세요.", 28, p.second, align = TextAlign.Center)
    }
}

@Composable private fun NavigationStatus(signal: Signal?) {
    val label = when {
        signal == null -> return
        signal.quality == "ERROR" || signal.value == "ERROR" -> "기본 내비에서 요청을 확인하지 못했어요."
        else -> when (signal.value) {
            "CONNECTING" -> "기본 내비 연결 중"
            "WAITING_FOR_PROVIDER" -> "기본 내비 준비 중"
            "OPENING" -> "기본 내비 열기 요청"
            "REQUESTED" -> "경유지 두 곳과 목적지 요청"
            "SEARCHING" -> "기본 내비가 경로를 찾는 중"
            "ROUTE_READY" -> "기본 내비에서 경유지 두 곳과 목적지 확인"
            "GUIDING_UNCONFIRMED" -> "기본 내비 안내 중 · 경유지 확인 필요"
            "CANCELLED" -> "기본 내비 안내 취소"
            "ARRIVED" -> "기본 내비에서 도착 알림"
            else -> return
        }
    }
    Copy(label, 26, LocalPalette.current.second)
}

@Composable private fun ReviewScreen(ui: PracticeUi, wide: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current; val s = ui.session
    val legacy = s?.rulesVersion == 1
    val completed = s?.turns.orEmpty().filter { it.exitMs != null }
    val event = completed.firstOrNull { it.id == ui.reviewEventId } ?: completed.firstOrNull { it.observation == Observation.UNANSWERED } ?: completed.firstOrNull()
    val direction = event?.direction?.label ?: "회전"
    val entryMs = (if (legacy) s?.passage?.entryMs else event?.entryMs) ?: s?.startedAtMs ?: 0
    val at = (s?.startedWallMs ?: 0) + entryMs - (s?.startedAtMs ?: 0)
    val time = SimpleDateFormat("H시 mm분", Locale.KOREA).format(Date(at))
    val scroll = key(event?.id) { rememberScrollState() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(scroll).padding(top = 20.dp)) {
            val place = event?.let { practiceTargetLabel(it.id.substringAfterLast(':')) }
            Copy(listOfNotNull(place, time).joinToString(" · "), 29, p.blue, FontWeight.Medium)
            Spacer(Modifier.height(16.dp))
            Copy(if (legacy) "왼쪽 차로로 옮겼나요?" else "여기서 ${direction}했나요?", if (wide) 66 else 54, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            Copy("동승자가 본 대로 골라 주세요. 기억이 안 나면 ‘보지 못했어요’를 눌러 주세요.", 30, p.second)
            if (!legacy) { Spacer(Modifier.height(20.dp)); Copy("조향·위치로 추정한 구간", 26, p.muted) }
            Spacer(Modifier.height(24.dp))
            val from = (if (legacy) s?.passage?.entryMs else event?.entryMs) ?: 0
            val until = (if (legacy) s?.passage?.exitMs else event?.exitMs) ?: from
            val turns = s?.samples?.filter { it.key == "turn" && it.atMs in from..until }.orEmpty()
            val values = turns.filter { it.quality == "VALID" }.mapNotNull {
                when (it.value) { "OFF" -> "꺼짐"; "LEFT" -> "왼쪽 켜짐"; "RIGHT" -> "오른쪽 켜짐"; else -> null }
            }.distinct()
            val label = if (values.isEmpty()) "깜빡이 값 없음" else "받은 깜빡이 값 · " + values.joinToString(", ")
            EvidenceCard("구간 안에서 받은 차량 기록", listOf(label, turnEvidence(event)).filter { it.isNotEmpty() }.joinToString("\n"))
        }
        Column(Modifier.widthIn(max = 1000.dp).fillMaxWidth().align(Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Action(if (legacy) "왼쪽으로 바꿨어요" else "${direction}했어요", !ui.saving, modifier = Modifier.fillMaxWidth()) { c.observe(if (legacy) Observation.LEFT else Observation.CONFIRMED) }
            Action(if (legacy) "왼쪽으로 바꾸지 않았어요" else "${direction}하지 않았어요", !ui.saving, secondary = true, modifier = Modifier.fillMaxWidth()) { c.observe(if (legacy) Observation.NOT_LEFT else Observation.NOT_PERFORMED) }
            Link("보지 못했어요", !ui.saving, Modifier.fillMaxWidth()) { c.observe(Observation.UNSEEN) }
        }
    }
}

@Composable private fun ResultScreen(ui: PracticeUi, wide: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current; val a = ui.assessment ?: ui.session?.let(PracticeEvaluator::evaluate)
    val s = ui.session
    val legacy = s?.rulesVersion == 1
    val assessments = s?.let(PracticeEvaluator::turnAssessments).orEmpty()
    val comparisons = s?.let(::practiceTargetOutcomes).orEmpty()
    val summary: @Composable ColumnScope.() -> Unit = {
        Copy(if (legacy) "이전 차로 변경 연습" else PracticeCourse.name, 28, p.second)
        Spacer(Modifier.height(18.dp))
        if (legacy) {
            Copy(a?.title ?: "연습 기록", if (a?.score != null) 96 else 50, p.navy, FontWeight.SemiBold)
            Copy(a?.reason.orEmpty(), 30, p.second)
            if (a?.score != null) Copy("46개 감점 항목 중 ${a.checked}개로 계산한 연습 점수예요.", 26, p.muted)
        } else {
            val title = when {
                s?.interrupted == true -> "기록이 중간에 끊겼어요"
                s?.safetyIssue == true -> "안전 문제로 평가를 보류했어요"
                comparisons.isNotEmpty() -> "다시 연습한 구간"
                a?.mistake == true -> "다시 연습할 깜빡이"
                assessments.isEmpty() -> "확인할 회전 기록이 없어요"
                assessments.all { it.status == "HELD" } -> "이번 깜빡이 평가는 보류했어요"
                else -> "구간별 깜빡이 기록"
            }
            Copy(title, if (wide) 60 else 46, p.navy, FontWeight.SemiBold)
            if (a?.title == "안전벨트 미착용 기록") Copy("주행 중 운전석 벨트 미착용 신호가 있었어요.", 28, p.second)
            comparisons.forEach { item ->
                Spacer(Modifier.height(18.dp))
                EvidenceCard(item.label, "지난번 · " + statusLabel(item.previousStatus) + "\n이번 · " + statusLabel(item.currentStatus))
            }
        }
    }
    val records: @Composable ColumnScope.() -> Unit = {
        PracticeCourse.turns.forEach { turn ->
            val result = assessments.firstOrNull { it.event.id.substringAfterLast(':') == turn.id }
            Spacer(Modifier.height(14.dp))
            EvidenceCard(practiceTargetLabel(turn.id), turnSummary(result, s))
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = summary)
            if (!legacy) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = records)
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { summary(); if (!legacy) records() }
        Spacer(Modifier.height(24.dp)); Action(if (a?.mistake == true) "오답노트 보기" else "기록 보기", modifier = Modifier.fillMaxWidth()) { c.go(Page.NOTEBOOK) }
        if (s != null && !s.interrupted) {
            if (legacy && s.passage?.exitMs != null) Link("확인 내용 수정", modifier = Modifier.fillMaxWidth()) { c.go(Page.REVIEW) }
            else s.turns.filter { it.exitMs != null }.forEach { event -> Link("${event.direction.label} 답변 수정", modifier = Modifier.fillMaxWidth()) { c.review(event.id) } }
        }
        Link("다음 연습 준비", modifier = Modifier.fillMaxWidth()) { c.go(Page.COURSE) }
    }
}

private fun statusLabel(status: String?): String = when (status) {
    "OMISSION" -> "깜빡이가 계속 꺼져 있었어요"
    "OBSERVED" -> "회전 방향 깜빡이를 켠 기록이 있어요"
    "NO_TURN" -> "회전하지 않았다고 답했어요"
    "NO_OPPORTUNITY" -> "확인할 회전 기록이 없어요"
    "UNANSWERED" -> "동승자 답변 대기"
    "HELD" -> "미평가"
    else -> "확인할 기록이 없어요"
}

private fun turnSummary(result: TurnAssessment?, session: PracticeSession?): String {
    if (result == null) return statusLabel("NO_OPPORTUNITY")
    val event = result.event
    if (result.status != "HELD") return statusLabel(result.status)
    return when {
        event.observation == Observation.UNSEEN -> "동승자가 보지 못해 미평가"
        event.observation == Observation.UNANSWERED -> "동승자 답변 대기"
        session?.safetyIssue == true -> "안전 문제로 미평가"
        session?.interrupted == true -> "기록이 중단돼 미평가"
        else -> result.reason
    }
}
@Composable private fun EvidenceCard(label: String, body: String) {
    val p = LocalPalette.current
    Column(Modifier.fillMaxWidth().background(p.light, RoundedCornerShape(27.dp)).padding(30.dp)) { Copy(label, 28, p.blue, FontWeight.Medium); Spacer(Modifier.height(24.dp)); Copy(body, 32) }
}
private fun repeatPracticeLabel(results: List<TurnAssessment>): String? {
    val directions = results.filter { it.mistake }.map { it.event.direction }.distinct()
    return when (directions.size) {
        0 -> null
        1 -> "${directions.single().label} 다시 연습"
        else -> "두 회전 다시 연습"
    }
}

@Composable private fun NotebookScreen(ui: PracticeUi, wide: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current; val a = ui.assessment; val s = ui.session
    val legacy = s?.rulesVersion == 1
    val turns = s?.let(PracticeEvaluator::turnAssessments).orEmpty()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
            Copy(if (a?.mistake == true) if (legacy) "차로 변경 전 깜빡이" else "회전 전 깜빡이" else "이번 연습 기록", if (wide) 66 else 52, weight = FontWeight.SemiBold)
            if (legacy) {
                Spacer(Modifier.height(28.dp))
                EvidenceCard("이전 차로 변경 기록", a?.reason.orEmpty())
            } else PracticeCourse.turns.forEach { course ->
                val result = turns.firstOrNull { it.event.id.substringAfterLast(':') == course.id }
                val observation = when (result?.event?.observation) {
                    Observation.CONFIRMED -> "동승자 · ${course.direction.label}했어요"
                    Observation.NOT_PERFORMED -> "동승자 · ${course.direction.label}하지 않았어요"
                    Observation.UNSEEN -> "동승자 · 보지 못했어요"
                    Observation.UNANSWERED -> "동승자 · 아직 답하지 않았어요"
                    else -> null
                }
                val detail = when (result?.status) {
                    "NO_TURN" -> "이번 깜빡이 평가에서 제외"
                    "OBSERVED" -> "차량 · ${if (course.direction == TurnDirection.LEFT) "왼쪽" else "오른쪽"} 깜빡이를 켠 기록이 있어요. 켠 시점은 평가하지 않았어요."
                    "OMISSION" -> "차량 · 회전 전후 깜빡이가 계속 꺼져 있었어요."
                    else -> turnSummary(result, s)
                }
                Spacer(Modifier.height(22.dp))
                EvidenceCard(practiceTargetLabel(course.id), listOfNotNull(observation, detail, turnEvidence(result?.event).takeIf { it.isNotBlank() }).joinToString("\n"))
            }
        }
        if (a?.mistake == true) {
            Action(if (legacy) "차로 변경 방법 보기" else "회전 연습 방법 보기", modifier = Modifier.fillMaxWidth()) { c.go(Page.LESSON) }
        }
        val repeatLabel = if (legacy) null else repeatPracticeLabel(turns)
        if (repeatLabel != null) Link(repeatLabel, !ui.saving, Modifier.fillMaxWidth(), onClick = c::recommend)
        else Link("다음 연습 준비", modifier = Modifier.fillMaxWidth()) { c.go(Page.BELT) }
    }
}
@Composable private fun LessonScreen(ui: PracticeUi, c: PracticeCoordinator) {
    val p = LocalPalette.current
    val legacy = ui.session?.rulesVersion == 1
    val steps = if (legacy) listOf("거울과 사각지대를 살펴요.", "깜빡이를 미리 켜요.", "옮기기 직전에 옆 차로를 다시 봐요.", "변경을 마칠 때까지 신호를 유지해요.")
        else listOf("주변 차와 보행자, 신호를 먼저 살펴요.", "회전할 방향의 깜빡이를 미리 켜요.", "충분히 속도를 낮추고 지도자의 안내를 따라요.", "회전을 마친 뒤 주변 교통을 다시 살펴요.")
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Copy("주변을 먼저 살피세요", 58, weight = FontWeight.SemiBold); Spacer(Modifier.height(50.dp))
            steps.forEachIndexed { i, text -> Copy("${i + 1}.  $text", 36, p.ink, modifier = Modifier.padding(vertical = 26.dp)) }
            Spacer(Modifier.height(40.dp)); Copy(if (legacy) "여유가 없으면 원래 차로로 진행하세요." else "회전할 수 있는 곳인지 도로의 표지와 신호를 확인하세요.", 32, p.second)
        }
        val repeatLabel = if (legacy) null else repeatPracticeLabel(ui.session?.let(PracticeEvaluator::turnAssessments).orEmpty())
        if (repeatLabel != null) Action(repeatLabel, !ui.saving, modifier = Modifier.fillMaxWidth(), onClick = c::recommend)
        else Action("다음 연습 준비", modifier = Modifier.fillMaxWidth()) { c.go(Page.BELT) }
    }
}
private fun turnEvidence(event: TurnEvent?): String = listOfNotNull(
    event?.steeringPeakDeg?.let { "조향: ${if (it < 0) "왼쪽" else if (it > 0) "오른쪽" else "가운데"}" },
    event?.headingChangeDeg?.let { "진행 방향: ${if (it < 0) "왼쪽" else "오른쪽"} ${abs(it).toInt()}°" },
).joinToString(" · ")
@Composable private fun Diagnostics(signals: Map<String, Signal>, c: PracticeCoordinator) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Link("내비 연결 확인", onClick = c::probeNavigation)
        if (BuildConfig.DEBUG) Link("기본 내비 설정", onClick = c::openNavigationSettings)
        signals.forEach { (key, s) -> Copy("$key   ${s.value ?: s.quality}", 26, weight = FontWeight.Medium); Copy("${s.source} · ${s.origin}${s.error?.let { "\n$it" } ?: ""}", 22, LocalPalette.current.second); Spacer(Modifier.height(20.dp)) }
    }
}
@Composable private fun LockScreen(recording: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Copy(if (recording) "주행 기록을 남기고 있어요" else "주차한 뒤에 이어서 확인해요", 54, weight = FontWeight.SemiBold, align = TextAlign.Center)
        Spacer(Modifier.height(30.dp)); Copy("차를 세우고 P단에 두세요.\n화면 조작이 가능해지면 이어서 볼 수 있어요.", 32, LocalPalette.current.second, align = TextAlign.Center)
    } }
}
@Composable private fun VideoHelp(resource: Int, onClose: () -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<VideoView?>(null) }
    DisposableEffect(Unit) { onDispose { player?.stopPlayback(); player = null } }
    Dialog(onDismissRequest = onClose) { Surface(shape = RoundedCornerShape(30.dp), color = LocalPalette.current.paper) { Column(Modifier.widthIn(max = 1200.dp).padding(32.dp)) {
        Copy(if (resource == R.raw.mirror_help) "사이드미러 맞추기" else "와이퍼 한 번 쓰기", 42, weight = FontWeight.SemiBold); Spacer(Modifier.height(24.dp))
        Copy("2025 그랜저 기준 안내예요. 내 차의 사용설명서도 확인하세요.", 24, LocalPalette.current.second)
        AndroidView(factory = { VideoView(it).apply { player = this; setVideoURI(Uri.parse("android.resource://${context.packageName}/$resource")); setOnPreparedListener { media -> media.isLooping = false; start() } } }, modifier = Modifier.fillMaxWidth().height(500.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) { Link("다시 보기", modifier = Modifier.weight(1f)) { player?.seekTo(0); player?.start() }; Action("닫기", modifier = Modifier.weight(1f), onClick = onClose) }
    } } }
}
