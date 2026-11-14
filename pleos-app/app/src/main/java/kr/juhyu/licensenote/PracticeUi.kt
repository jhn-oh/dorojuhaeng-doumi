package kr.juhyu.licensenote

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.text.style.LineBreak
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

@Composable internal fun Copy(text: String, size: Int = 34, color: Color = LocalPalette.current.ink, weight: FontWeight = FontWeight.Normal, align: TextAlign = TextAlign.Start, modifier: Modifier = Modifier, style: TextStyle = LocalTextStyle.current) {
    val textScale = LocalAppTextScale.current
    Text(text, modifier, color, fontSize = (size * textScale).sp, fontWeight = weight, fontFamily = Asta, lineHeight = (size * 1.36 * textScale).sp, textAlign = align, style = style)
}
@Composable internal fun Action(text: String, enabled: Boolean = true, secondary: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = LocalPalette.current
    Button(onClick, modifier.heightIn(min = 92.dp), enabled = enabled, shape = RoundedCornerShape(22.dp), colors = ButtonDefaults.buttonColors(containerColor = if (secondary) p.light else p.blue, contentColor = if (secondary) p.ink else p.onAction), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 18.dp)) {
        Copy(text, 34, if (enabled) if (secondary) p.ink else p.onAction else p.muted, FontWeight.SemiBold, TextAlign.Center)
    }
}
@Composable internal fun Link(text: String, enabled: Boolean = true, modifier: Modifier = Modifier, onClick: () -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 76.dp), enabled = enabled, shape = RoundedCornerShape(17.dp)) { Copy(text, 29, if (enabled) LocalPalette.current.blue else LocalPalette.current.muted, FontWeight.Medium, TextAlign.Center) }
}
@Composable internal fun FigmaIcon(id: Int, size: Int, color: Color? = LocalPalette.current.blue, mirrored: Boolean = false, assetOverride: String? = null) {
    val context = LocalContext.current
    val asset = assetOverride ?: when (id) {
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
                        else if (!parked && ui.page != Page.DRIVING && ui.page != Page.GUIDE) LockScreen(ui.session?.phase == SessionPhase.RECORDING)
                        else Box(Modifier.weight(1f)) {
                            when (ui.page) {
                                Page.WELCOME -> Preparation(wide, R.drawable.figma_warning, 0, "연습면허로 운전하나요?", "연습운전면허와 주행연습 표지를 준비하세요.\n이 차종 면허를 딴 지 2년이 지났고 면허가 유효한 분에게 지도를 받으세요.", { Action("확인했어요", modifier = Modifier.fillMaxWidth(), onClick = c::acceptIntro); Link("이미 운전면허가 있어요", modifier = Modifier.fillMaxWidth(), onClick = c::acceptLicensed) })
                                Page.BELT -> {
                                    val belt = signals["belt"]
                                    val worn = belt.driverBeltFastened(ui.now)
                                    val description = when {
                                        worn -> "운전석 버클이 채워졌어요."
                                        belt.fresh(ui.now) -> "차에 타면 운전석 안전벨트부터 매세요.\n버클이 채워지면 알려드릴게요."
                                        belt?.quality == "UNSUPPORTED" -> "이 차는 운전석 버클 확인을 지원하지 않아요.\n벨트를 매고 직접 확인해 주세요."
                                        belt?.quality == "DENIED" -> "버클을 읽을 권한이 없어요.\n벨트를 매고 직접 확인해 주세요."
                                        belt?.quality == "ERROR" -> "버클 신호를 다시 연결하고 있어요.\n벨트를 매고 직접 확인해도 돼요."
                                        else -> "운전석 버클 신호를 기다리고 있어요.\n벨트를 매고 직접 확인해도 돼요."
                                    }
                                    Preparation(wide, R.drawable.figma_belt, 1, if (worn) "안전벨트를 맸어요" else if (belt.fresh(ui.now)) "안전벨트를 매세요" else "안전벨트를 확인해 주세요", description, {
                                        if (worn) Action("다음", modifier = Modifier.fillMaxWidth()) { c.confirmBelt(false) }
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            Link("계기판 읽기", modifier = Modifier.weight(1f)) { c.openGuide(GuideTopic.SPEED) }
                                            Link(if (belt.fresh(ui.now)) "직접 확인했어요" else "벨트를 맸어요", modifier = Modifier.weight(1f)) { c.confirmBelt(true) }
                                        }
                                    })
                                }
                                Page.MIRROR -> Preparation(wide, R.drawable.figma_mirror, 2, "거울을 맞춰 주세요", "먼저 좌석을 몸에 맞추세요.\n룸미러와 양쪽 사이드미러가 잘 보이나요?", {
                                    Link("조절 방법 보기", modifier = Modifier.fillMaxWidth()) { video = R.raw.mirror_help }
                                    Action("잘 보여요", modifier = Modifier.fillMaxWidth()) { c.go(Page.DOORS) }
                                })
                                Page.DOORS -> {
                                    val doors = departureDoors(signals, ui.now)
                                    val open = doors.firstOpen?.let(::doorName)
                                    Preparation(wide, R.drawable.figma_door, 3, if (doors.allClosed) "문을 모두 닫았어요" else if (open != null) "열린 곳이 있어요 · $open" else "문이 모두 닫혔나요?", if (doors.allClosed) "차 앞뒤와 양옆을 살펴보세요.\n사람이나 장애물이 있나요?" else "문을 닫고 차 앞뒤와 양옆을 살펴보세요.", {
                                        Link("와이퍼 사용법", modifier = Modifier.fillMaxWidth()) { video = R.raw.wiper_help }
                                        val action = when {
                                            open != null -> "열린 문을 닫아 주세요"
                                            !doors.allClosed -> "모두 닫혔어요"
                                            ui.resumePreviewAfterChecks -> "선택한 코스로 돌아가기"
                                            else -> "코스 보기"
                                        }
                                        Action(action, enabled = open == null, modifier = Modifier.fillMaxWidth()) { c.confirmDoors(!doors.allClosed) }
                                    })
                                }
                                Page.COURSE -> CourseScreen(ui, signals, wide, c)
                                Page.DRIVING -> DrivingScreen(ui, signals, wide, parked, c)
                                Page.RESULT -> ResultScreen(ui, wide, c)
                                Page.SHARE -> ui.session?.let { ReceiverScreen(it, c::back) }
                                Page.NOTEBOOK -> NotebookScreen(ui, wide, c)
                                Page.LESSON -> LessonScreen(ui, c)
                                Page.DIAGNOSTICS -> Diagnostics(signals, c)
                                Page.GUIDE -> GuideScreen(ui.guideTopic, signals, ui.now, wide, ui.guideReturnPage, parked, c::closeGuide, c::openGuideTopic)
                            }
                        }
                        ui.error?.let { error -> Copy(error, 26, p.blue); if (error.startsWith("기록을 저장") && ui.session != null) Link("다시 저장하기", onClick = c::retrySave) }
                        if (ui.saving) Copy("기록을 저장하고 있어요", 26, p.second)
                    }
                    if (video != null && parked) VideoHelp(video!!, signals, ui.now, onClose = { video = null })
                    if (legal && parked) Dialog(onDismissRequest = { legal = false }) {
                        Surface(shape = RoundedCornerShape(30.dp), color = p.paper) { Column(Modifier.widthIn(max = 1100.dp).padding(48.dp).verticalScroll(rememberScrollState())) {
                            Copy("연습면허와 주행연습 표지", 46, weight = FontWeight.SemiBold)
                            Spacer(Modifier.height(24.dp))
                            Copy("내 연습운전면허", 33, weight = FontWeight.SemiBold)
                            Copy("기능시험 합격 후 연습운전면허를 발급받으세요. 발급일부터 1년간 유효하고, 그 전에 정식 운전면허를 받으면 효력이 끝나요.", 29)
                            Spacer(Modifier.height(18.dp))
                            Copy("동승 지도자", 33, weight = FontWeight.SemiBold)
                            Copy("이 차종 면허를 받은 지 2년이 지났고 면허가 유효한 분에게 지도를 받으세요.", 29)
                            Spacer(Modifier.height(18.dp))
                            Copy("주행연습 표지", 33, weight = FontWeight.SemiBold)
                            Copy("2종 보통 승용차는 운전석에서 보아 앞유리 오른쪽 아래와 뒷유리 가운데 위에 붙이세요. 1종 보통 연습면허의 뒤쪽 표지는 뒤 적재함 중앙에 붙이세요.", 29)
                            Spacer(Modifier.height(18.dp))
                            Copy("연습 목적으로 운전하세요. 차량 사용 동의와 보험 보장도 확인하세요.", 28, p.second)
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
            text = { Copy(if (reported) "저장한 차량 기록으로 복습을 다시 열어요." else "이번 연습의 복습을 보류하고 차량 기록은 보관해요.", 28) },
            confirmButton = { Link(if (reported) "기록 취소" else "안전 문제 기록", !ui.saving) { confirmIssue = false; c.setSafetyIssue(!reported) } },
            dismissButton = { Link("돌아가기") { confirmIssue = false } },
        )
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 83.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            if (ui.page !in setOf(Page.WELCOME, Page.DRIVING, Page.RESULT)) IconButton(onClick = c::back, enabled = parked, modifier = Modifier.size(76.dp).semantics { contentDescription = "뒤로" }) { FigmaIcon(R.drawable.figma_back, 64, LocalPalette.current.second) }
            Copy(when(ui.page) { Page.WELCOME, Page.DRIVING -> "운전 연습"; Page.BELT, Page.MIRROR, Page.DOORS -> "출발 준비"; Page.COURSE -> when (ui.courseStep) { CourseStep.PREVIEW -> "경로 미리보기"; CourseStep.UTURN_LESSON, CourseStep.TRAFFIC_LESSON -> "정차 중 학습"; else -> "연습 코스" }; Page.RESULT -> "연습 결과"; Page.SHARE -> "휴대폰으로 보내기"; Page.NOTEBOOK -> if (ui.session?.vehicleReview == true) "주행 복습" else "오답노트"; Page.LESSON -> if (ui.session?.practiceRoute?.isLanePractice == true) "차로 변경 연습" else "회전 연습"; Page.DIAGNOSTICS -> "SDK 연결 확인"; Page.GUIDE -> "운전 설명서" }, 29, LocalPalette.current.second, FontWeight.Medium)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canChangeText && ui.page.hasGuideEntry()) Link("설명서") { c.openGuide() }
            if (canChangeText) Link("글자", modifier = Modifier.semantics { contentDescription = "글자 크기: ${if (ui.textScale > 1f) "크게" else "기본"}" }) { textSizeDialog = true }
            when (ui.page) {
                Page.WELCOME -> Link("면허와 표지", parked, onClick = onLegal)
                Page.BELT, Page.MIRROR, Page.DOORS -> Copy("${ui.page.ordinal} / 3", 34, LocalPalette.current.blue, FontWeight.SemiBold)
                Page.RESULT -> if (ui.session?.vehicleReview == true) Link(if (reported) "안전 문제 수정" else "안전 문제", parked && !ui.saving && ui.session != null) { confirmIssue = true }
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
            Spacer(Modifier.height(30.dp)); Copy(body, if (wide) 40 else 34, p.second, align = TextAlign.Center, modifier = Modifier.widthIn(max = 1080.dp), style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading))
        }
        Column(Modifier.widthIn(max = 784.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), content = actions)
    }
}

@Composable private fun CourseScreen(ui: PracticeUi, signals: Map<String, Signal>, wide: Boolean, c: PracticeCoordinator) {
    if (ui.courseStep != CourseStep.PREVIEW) {
        CoursePicker(ui, wide, c)
        return
    }
    val p = LocalPalette.current
    ui.chosenNative?.let { dest ->
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            Copy("기본 내비에서 선택했어요", 32, p.second)
            Copy(dest.name, if (wide) 72 else 56, weight = FontWeight.SemiBold)
            Copy("남은 거리 ${dest.meters} m", 40, p.blue)
            Copy("현재 기본 내비가 정한 경로로 운전해요. 연습 구간·차로 수·도로 종류는 평가하지 않아요.", 30, p.second)
            Spacer(Modifier.weight(1f))
            Action("이 목적지로 연습 시작", enabled = !ui.saving && ui.searchResult?.let(dest::samePlace) == true, modifier = Modifier.fillMaxWidth(), onClick = c::start)
            Link("다른 목적지 고르기", modifier = Modifier.fillMaxWidth(), onClick = c::back)
        }
        return
    }
    val route = CourseCatalog.find(ui.selectedRouteId) ?: return
    val recommendation = remember(ui.session, route.id) { CourseCatalog.recommendation(ui.session) }
    Column(Modifier.fillMaxSize()) {
        val text: @Composable ColumnScope.() -> Unit = {
            Copy(route.name, if (wide) 74 else 58, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(20.dp))
            Copy(route.exercises, 34, p.second)
            if (recommendation == route) EvidenceCard("이번 연습 목표", "지난번과 같은 구간에서 회전 방향의 깜빡이 기록을 비교해 보세요.")
            Spacer(Modifier.height(32.dp))
            Copy("${route.distance} m   약 ${route.minutes}분", 58, p.blue, FontWeight.SemiBold)
            Copy(if (route.verifiedArterial) "Valhalla 자동차 경로 예상 · 실시간 교통 미반영" else if (route.routingSeconds != null) "OSRM 자동차 경로 예상 · 실시간 교통 미반영" else "모의 주행 속도 15 km/h 기준", 26, p.muted)
            if (route.verifiedArterial) Copy("편도 차로 수는 국가교통정보센터 표준링크 자료와 대조했어요. 기본 내비에서 다른 길로 안내하면 이 조건을 다시 확인하세요.", 26, p.second)
            Copy("${route.departure} → ${route.destination}", 30, p.second)
            Copy(if (route.isLanePractice) "에뮬레이터 전용 가상 구간이에요." else "에뮬레이터용 코스 · 실제 도로 통행 조건 미확인", 26, p.muted)
            Spacer(Modifier.height(20.dp))
            Copy(if (route.waypoints.isEmpty()) "시작하면 기본 내비의 기존 목적지를 이 도착점으로 변경해요." else "시작하면 기본 내비의 기존 경로를 경유지 ${route.waypoints.size}곳과 이 목적지로 변경해요.", 28, p.second)
        }
        if (wide) Row(Modifier.weight(1f).fillMaxWidth().padding(top = 60.dp), horizontalArrangement = Arrangement.spacedBy(70.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = text)
            CourseMap(locationPoint(signals["location"], ui.now), Modifier.weight(1f).height(570.dp), route = route)
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { text(); Spacer(Modifier.height(30.dp)); CourseMap(locationPoint(signals["location"], ui.now), Modifier.fillMaxWidth().height(330.dp), route = route) }
        Action("이 코스로 시작", enabled = !ui.saving, modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth().align(Alignment.CenterHorizontally), onClick = c::start)
        Link("다른 코스 보기", modifier = Modifier.fillMaxWidth(), onClick = c::back)
    }
}

@Composable private fun DrivingScreen(ui: PracticeUi, signals: Map<String, Signal>, wide: Boolean, parked: Boolean, c: PracticeCoordinator) {
    val s = ui.session ?: return
    val p = LocalPalette.current
    s.nativeDestination?.let { destination ->
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Copy("기본 내비 안내", 29, p.blue)
            Copy(destination.name, if (wide) 56 else 44, weight = FontWeight.SemiBold)
            Copy("길과 방향은 기본 내비에서 확인하세요.", 28, p.second)
            DriveMotionMeter(signals["speed"])
            DriveHelp(s, parked, signals, ui.now)
            Spacer(Modifier.weight(1f))
            Link("기본 내비 열기", onClick = c::openNavigation)
            if (parked) Action("기록 종료", enabled = !ui.saving, modifier = Modifier.fillMaxWidth(), onClick = c::finish)
        }
        return
    }
    val instruction = driveCue(s, signals, ui.now)
    val navigation = signals["navigation_request"]?.takeIf { it.receivedMs >= s.startedAtMs }
    val elapsed = ((ui.now - s.startedAtMs) / 1000).coerceAtLeast(0)
    val subtitle = if (s.practiceTargets.isEmpty()) instruction.source else
        instruction.source + " · 복습 코스"
    val next: @Composable () -> Unit = {
        Copy(subtitle, if (wide) 26 else 23, p.blue, FontWeight.Medium)
        Copy(instruction.title, if (wide) 58 else 47, p.ink, FontWeight.SemiBold, TextAlign.Center, style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading))
    }
    val tools: @Composable () -> Unit = {
        DriveMotionMeter(signals["speed"])
        DriveHelp(s, parked, signals, ui.now)
        val mode = signals["drive_mode"]?.takeIf { it.fresh(ui.now, 3500) }?.value
        val label = when (mode) { "NORMAL" -> "일반"; "ECO" -> "에코"; "SPORT" -> "스포츠"; else -> null }
        Copy("기록 중 · ${elapsed / 60}분 ${elapsed % 60}초" + (label?.let { " · ${it} 모드" } ?: ""), 22, p.second)
    }
    val point = locationPoint(signals["location"], ui.now)
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                CourseMap(point, Modifier.weight(1f).fillMaxHeight(), linksEnabled = parked, route = s.practiceRoute)
                Column(Modifier.weight(1f).then(if (parked) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    next(); tools()
                }
            }
        } else {
            next()
            Spacer(Modifier.height(12.dp))
            CourseMap(point, Modifier.fillMaxWidth().weight(1f), linksEnabled = parked, route = s.practiceRoute)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                DriveMotionMeter(signals["speed"])
                Box(Modifier.widthIn(max = 340.dp)) { DriveHelp(s, parked, signals, ui.now) }
            }
            Copy("기록 중 · ${elapsed / 60}분 ${elapsed % 60}초", 22, p.second)
        }
        if (parked) {
            NavigationStatus(navigation)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Link("기본 내비 열기", modifier = Modifier.weight(1f)) { c.openNavigation() }
                Action(if (ui.saving) "저장 중" else "기록 종료", enabled = !ui.saving, modifier = Modifier.weight(1f), onClick = c::finish)
            }
        } else {
            Link("기본 내비 열기") { c.openNavigation() }
        }
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
            "REQUESTED" -> "선택한 경유지와 목적지 요청"
            "SEARCHING" -> "기본 내비가 경로를 찾는 중"
            "ROUTE_READY" -> "기본 내비에서 선택한 경유지와 목적지 확인"
            "GUIDING_UNCONFIRMED" -> "기본 내비 안내 중 · 경유지 확인 필요"
            "CANCELLED" -> "기본 내비 안내 취소"
            "ARRIVED" -> "기본 내비에서 도착 알림"
            else -> return
        }
    }
    Copy(label, 26, LocalPalette.current.second)
}

@Composable private fun ResultScreen(ui: PracticeUi, wide: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current; val a = ui.assessment ?: ui.session?.let(PracticeEvaluator::evaluate)
    val s = ui.session
    if (s?.vehicleReview == true && a != null) {
        VehicleResultScreen(s, a, wide, ui.saving, c)
        return
    }
    val legacy = s?.rulesVersion == 1
    val assessments = s?.let(PracticeEvaluator::turnAssessments).orEmpty()
    val comparisons = s?.let(::practiceTargetOutcomes).orEmpty()
    val summary: @Composable ColumnScope.() -> Unit = {
        Copy("이전 방식의 연습 · " + (s?.practiceRoute?.name ?: "연습 기록"), 28, p.second)
        Spacer(Modifier.height(18.dp))
        s?.endedAtMs?.let { end ->
            val seconds = ((end - s.startedAtMs) / 1000).coerceAtLeast(0)
            Copy("연습 시간 · ${seconds / 60}분 ${seconds % 60}초", 25, p.muted)
        }
        if (legacy) {
            Copy(a?.title ?: "연습 기록", if (a?.score != null) 96 else 50, p.navy, FontWeight.SemiBold)
            Copy(a?.reason.orEmpty(), 30, p.second)
            if (a?.score != null) {
                Copy("공식 도로주행시험 점수가 아니에요.", 28, p.second)
                Copy("46개 감점 항목 중 ${a.checked}개를 확인했어요.", 26, p.muted)
            }
        } else {
            val title = when {
                s?.nativeDestination != null -> a?.title ?: "목적지 연습 기록"
                s?.interrupted == true -> "기록이 중간에 끊겼어요"
                s?.safetyIssue == true -> "안전 문제로 평가를 보류했어요"
                comparisons.isNotEmpty() -> "다시 연습한 구간"
                a?.mistake == true -> "다시 연습할 깜빡이"
                assessments.isEmpty() -> "확인할 회전 기록이 없어요"
                assessments.all { it.status == "HELD" } -> "이번 깜빡이 평가는 보류했어요"
                else -> "구간별 깜빡이 기록"
            }
            Copy(title, if (wide) 60 else 46, p.navy, FontWeight.SemiBold)
            Copy(if (s?.nativeDestination != null) "기본 내비가 안내한 연습이에요. 차량 기록을 확인하세요." else "회전 ${a?.checked ?: 0}곳의 방향지시등을 확인했어요. 이 연습은 숫자 점수를 보류해요.", 26, p.second)
            if (a?.title == "안전벨트 미착용 기록") Copy("주행 중 운전석 벨트 미착용 신호가 있었어요.", 28, p.second)
            comparisons.forEach { item ->
                Spacer(Modifier.height(18.dp))
                EvidenceCard(item.label, "지난번 · " + statusLabel(item.previousStatus) + "\n이번 · " + statusLabel(item.currentStatus))
            }
        }
        if (s != null) {
            VehicleDriveSummary(s)
            PastDriveQuestions(s.id)
            if (legacy && s.passage?.exitMs != null) SavedObservations(s)
        }
    }
    val records: @Composable ColumnScope.() -> Unit = {
        (s?.practiceRoute ?: CourseCatalog.full).turns.forEach { turn ->
            val result = assessments.firstOrNull { it.event.id.substringAfterLast(':') == turn.id }
            Spacer(Modifier.height(14.dp))
            EvidenceCard(practiceTargetLabel(turn.id, s?.practiceRoute ?: CourseCatalog.full), turnSummary(result, s))
        }
        if (s != null && s.turns.any { it.exitMs != null }) SavedObservations(s)
    }
    Column(Modifier.fillMaxSize()) {
        if (wide) Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = summary)
            if (!legacy) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = records)
        } else Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { summary(); if (!legacy) records() }
        Spacer(Modifier.height(24.dp)); Action(if (a?.mistake == true) "오답노트 보기" else "기록 보기", modifier = Modifier.fillMaxWidth()) { c.go(Page.NOTEBOOK) }
        if (s != null) Action("휴대폰으로 보내기", secondary = true, modifier = Modifier.fillMaxWidth()) { c.go(Page.SHARE) }
        Link("다음 연습 준비", modifier = Modifier.fillMaxWidth()) { c.go(Page.COURSE) }
    }
}

@Composable private fun SavedObservations(session: PracticeSession) {
    var open by rememberSaveable(session.id) { mutableStateOf(false) }
    Link(if (open) "이전 확인 내용 닫기" else "이전 확인 내용 보기") { open = !open }
    if (open) {
        val notes = if (session.rulesVersion == 1) listOf("차로 변경 · " + archivedAnswer(session.observation))
        else session.turns.filter { it.exitMs != null }.map { event ->
            practiceTargetLabel(event.id.substringAfterLast(':'), session.practiceRoute) + " · " + archivedAnswer(event.observation)
        }
        EvidenceCard("당시 저장한 답변", notes.joinToString("\n"))
    }
}

private fun archivedAnswer(answer: Observation): String = when (answer) {
    Observation.LEFT -> "왼쪽으로 옮겼어요"
    Observation.NOT_LEFT -> "왼쪽으로 옮기지 않았어요"
    Observation.CONFIRMED -> "회전했어요"
    Observation.NOT_PERFORMED -> "회전하지 않았어요"
    Observation.UNSEEN -> "보지 못했어요"
    Observation.UNANSWERED -> "저장된 답변 없음"
}

/** New sessions open straight to the captured signals. The details page carries the longer evidence. */
@Composable private fun VehicleResultScreen(s: PracticeSession, a: Assessment, wide: Boolean, saving: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current
    val results = remember(s) { PracticeEvaluator.turnAssessments(s) }
    val comparisons = remember(s) { practiceTargetOutcomes(s).associateBy { it.courseTurnId } }
    val repeat = repeatPracticeLabel(results).takeIf { a.practiceSuggested }
    val summary: @Composable () -> Unit = {
        val seconds = s.endedAtMs?.let { ((it - s.startedAtMs) / 1000).coerceAtLeast(0) }
        Copy(listOfNotNull(s.practiceRoute.name, seconds?.let { "${it / 60}분 ${it % 60}초" }).joinToString(" · "), 28, p.second)
        Spacer(Modifier.height(20.dp))
        Copy(a.title, if (wide) 60 else 48, p.navy, FontWeight.SemiBold, style = LocalTextStyle.current.copy(lineBreak = LineBreak.Heading))
        Spacer(Modifier.height(12.dp))
        Copy(a.reason, if (wide) 32 else 28, p.second, style = LocalTextStyle.current.copy(lineBreak = LineBreak.Paragraph))
    }
    val segments: @Composable () -> Unit = {
        s.practiceRoute.turns.forEach { turn ->
            val result = results.firstOrNull { it.event.id.endsWith(":${turn.id}") }
            val prior = comparisons[turn.id]?.previousStatus
            val label = practiceTargetLabel(turn.id, s.practiceRoute)
            Column(Modifier.fillMaxWidth().background(p.light, RoundedCornerShape(22.dp)).padding(horizontal = 28.dp, vertical = 20.dp)) {
                Copy(label, 26, p.blue, FontWeight.Medium)
                Copy(signalReading(result?.status, turn.direction), if (wide) 39 else 32, p.ink, FontWeight.SemiBold)
                if (prior != null) Copy("지난번 · " + signalReading(prior, turn.direction), 24, p.second)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (wide && s.practiceRoute.turns.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { summary() }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { segments() }
            }
        } else Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            summary()
            if (s.practiceRoute.turns.isNotEmpty()) {
                Spacer(Modifier.height(22.dp))
                segments()
            }
        }
        Spacer(Modifier.height(16.dp))
        Action(repeat ?: "다음 연습 준비", enabled = !saving, modifier = Modifier.fillMaxWidth()) {
            if (repeat != null) c.recommend() else c.go(Page.BELT)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Link("차량 기록 보기", modifier = Modifier.weight(1f)) { c.go(Page.NOTEBOOK) }
            Link("휴대폰으로 보내기", modifier = Modifier.weight(1f)) { c.go(Page.SHARE) }
        }
    }
}

private fun statusLabel(status: String?): String = when (status) {
    "OMISSION" -> "깜빡이가 계속 꺼져 있었어요"
    "OBSERVED" -> "회전 방향 깜빡이를 켠 기록이 있어요"
    "NO_TURN" -> "회전하지 않았다고 답했어요"
    "NO_OPPORTUNITY" -> "확인할 회전 기록이 없어요"
    "UNANSWERED" -> "저장된 답변 없음"
    "HELD" -> "미평가"
    else -> "확인할 기록이 없어요"
}

private fun turnSummary(result: TurnAssessment?, session: PracticeSession?): String {
    if (result == null) return statusLabel("NO_OPPORTUNITY")
    val event = result.event
    if (result.status != "HELD") return statusLabel(result.status)
    return when {
        event.observation == Observation.UNSEEN -> "동승자가 보지 못해 미평가"
        event.observation == Observation.UNANSWERED -> "당시 답변이 저장되지 않았어요"
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
    val directions = results.filter { it.practiceSuggested }.map { it.event.direction }.distinct()
    return when (directions.size) {
        0 -> null
        1 -> "${directions.single().label} 다시 연습"
        else -> "두 회전 다시 연습"
    }
}

@Composable private fun NotebookScreen(ui: PracticeUi, wide: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current; val a = ui.assessment; val s = ui.session
    if (s?.vehicleReview == true) {
        VehicleNotebook(s, wide, ui.saving, c)
        return
    }
    val legacy = s?.rulesVersion == 1
    val turns = s?.let(PracticeEvaluator::turnAssessments).orEmpty()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
            Copy(if (a?.mistake == true) if (legacy) "차로 변경 전 깜빡이" else "회전 전 깜빡이" else "이번 연습 기록", if (wide) 66 else 52, weight = FontWeight.SemiBold)
            if (s?.nativeDestination != null) {
                Spacer(Modifier.height(22.dp))
                EvidenceCard(s.nativeDestination.name, "기본 내비에서 선택한 목적지예요. 이 연습은 주행 기록만 남겨요.")
            } else if (legacy) {
                Spacer(Modifier.height(28.dp))
                EvidenceCard("차로 변경 기록", a?.reason.orEmpty())
            } else s?.practiceRoute?.turns.orEmpty().forEach { course ->
                val result = turns.firstOrNull { it.event.id.substringAfterLast(':') == course.id }
                val observation = when (result?.event?.observation) {
                    Observation.CONFIRMED -> "동승자 · ${course.direction.label}했어요"
                    Observation.NOT_PERFORMED -> "동승자 · ${course.direction.label}하지 않았어요"
                    Observation.UNSEEN -> "동승자 · 보지 못했어요"
                    Observation.UNANSWERED -> "당시 저장된 답변 없음"
                    else -> null
                }
                val detail = when (result?.status) {
                    "NO_TURN" -> "이번 깜빡이 평가에서 제외"
                    "OBSERVED" -> "차량 · ${if (course.direction == TurnDirection.LEFT) "왼쪽" else "오른쪽"} 깜빡이를 켠 기록이 있어요. 켠 시점은 평가하지 않았어요."
                    "OMISSION" -> "차량 · 회전 전후 깜빡이가 계속 꺼져 있었어요."
                    else -> turnSummary(result, s)
                }
                Spacer(Modifier.height(22.dp))
                EvidenceCard(practiceTargetLabel(course.id, s?.practiceRoute ?: CourseCatalog.full), listOfNotNull(observation, detail, turnEvidence(result?.event).takeIf { it.isNotBlank() }).joinToString("\n"))
            }
        }
        if (s != null) PreviousPracticeList(s.id, c)
        if (a?.mistake == true) {
            Action(if (legacy) "차로 변경 방법 보기" else "회전 연습 방법 보기", modifier = Modifier.fillMaxWidth()) { c.go(Page.LESSON) }
        }
        val repeatLabel = if (legacy && a?.mistake == true) "차로 변경 다시 연습" else repeatPracticeLabel(turns)
        if (repeatLabel != null) Link(repeatLabel, !ui.saving, Modifier.fillMaxWidth(), onClick = c::recommend)
        else Link("다음 연습 준비", modifier = Modifier.fillMaxWidth()) { c.go(Page.BELT) }
    }
}

@Composable private fun VehicleNotebook(s: PracticeSession, wide: Boolean, saving: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current
    val results = remember(s) { PracticeEvaluator.turnAssessments(s) }
    val a = remember(s) { PracticeEvaluator.evaluate(s) }
    val repeat = repeatPracticeLabel(results).takeIf { a.practiceSuggested }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Copy(s.practiceRoute.name, 28, p.second)
            Copy(if (s.practiceRoute.turns.isEmpty()) "이번 차량 기록" else "구간별 깜빡이 기록",
                if (wide) 58 else 46, p.navy, FontWeight.SemiBold)
            if (s.practiceRoute.turns.isEmpty()) Copy(a.reason, 30, p.second)
            else {
                Copy("위치와 조향으로 회전 구간을 추정했어요.", 28, p.second)
                for (turn in s.practiceRoute.turns) {
                    val result = results.firstOrNull { it.event.id.endsWith(":${turn.id}") }
                    val evidence = result?.event?.let(::turnEvidence).orEmpty()
                    val detail = listOfNotNull(
                        result?.reason ?: "이 구간에서 위치와 조향이 일치하는 기록을 찾지 못했어요.",
                        evidence.takeIf { it.isNotBlank() }
                    ).joinToString("\n")
                    EvidenceCard(practiceTargetLabel(turn.id, s.practiceRoute), detail)
                }
            }
            VehicleDriveSummary(s)
            PastDriveQuestions(s.id)
            if (repeat != null) Link("회전 연습 방법 보기", modifier = Modifier.fillMaxWidth()) { c.go(Page.LESSON) }
            PreviousPracticeList(s.id, c)
        }
        Spacer(Modifier.height(18.dp))
        Action(repeat ?: "다음 연습 준비", !saving, modifier = Modifier.fillMaxWidth()) {
            if (repeat != null) c.recommend() else c.go(Page.BELT)
        }
    }
}
@Composable private fun PreviousPracticeList(currentId: String, coordinator: PracticeCoordinator) {
    var open by remember(currentId) { mutableStateOf(false) }
    var offset by remember(currentId) { mutableIntStateOf(0) }
    var page by remember(currentId) { mutableStateOf<List<PreviousPractice>?>(null) }
    var error by remember(currentId) { mutableStateOf(false) }
    val format = remember { SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA) }
    Link("지난 연습 기록", modifier = Modifier.fillMaxWidth()) { open = true; offset = 0 }
    if (!open) return
    LaunchedEffect(currentId, offset) {
        page = null; error = false
        val requested = offset
        coordinator.previousPractices(currentId, offset) { result ->
            if (open && requested == offset) { page = result.getOrNull(); error = result.isFailure }
        }
    }
    Dialog(onDismissRequest = { open = false }) {
        Surface(shape = RoundedCornerShape(28.dp), color = LocalPalette.current.paper) {
            Column(Modifier.padding(32.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Copy("지난 연습 기록", 44, weight = FontWeight.SemiBold)
                if (error) Copy("기록을 불러올 수 없어요.", 28)
                else if (page == null) Copy("기록을 불러오고 있어요.", 28)
                else if (page!!.isEmpty()) Copy("앞선 연습 기록이 없어요.", 28)
                page.orEmpty().forEach { item -> EvidenceCard(format.format(Date(item.started)) + " · " + item.title, item.notes.joinToString("\n")) }
                Row {
                    if (offset > 0) Link("더 최근 기록") { offset -= 8 }
                    if (page?.size == 8) Link("더 이전 기록") { offset += 8 }
                }
                Action("닫기", modifier = Modifier.fillMaxWidth()) { open = false }
            }
        }
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
@Composable internal fun LockScreen(recording: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Copy(if (recording) "주행 기록을 남기고 있어요" else "주차한 뒤에 이어서 확인해요", 54, weight = FontWeight.SemiBold, align = TextAlign.Center)
        Spacer(Modifier.height(30.dp)); Copy("차를 세우고 P단에 두세요.\n화면 조작이 가능해지면 이어서 볼 수 있어요.", 32, LocalPalette.current.second, align = TextAlign.Center)
    } }
}
@Composable private fun VideoHelp(resource: Int, signals: Map<String, Signal>, now: Long, onClose: () -> Unit) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<VideoView?>(null) }
    val opened = remember(resource) { android.os.SystemClock.elapsedRealtime() }
    val activation = signals["wiper_front_activation"]
    LaunchedEffect(resource, activation) {
        if (resource == R.raw.wiper_help && observedWiperActivation(activation, opened, android.os.SystemClock.elapsedRealtime())) onClose()
    }
    DisposableEffect(Unit) { onDispose { player?.stopPlayback(); player = null } }
    Dialog(onDismissRequest = onClose) { Surface(shape = RoundedCornerShape(30.dp), color = LocalPalette.current.paper) { Column(Modifier.widthIn(max = 1200.dp).padding(32.dp)) {
        Copy(if (resource == R.raw.mirror_help) "사이드미러 맞추기" else "와이퍼 한 번 쓰기", 42, weight = FontWeight.SemiBold); Spacer(Modifier.height(24.dp))
        Copy("2025 그랜저 기준 안내예요. 내 차의 사용설명서도 확인하세요.", 24, LocalPalette.current.second)
        if (resource == R.raw.wiper_help) Copy(wiperLabel(signals["wiper_front"], now), 23, LocalPalette.current.navy)
        AndroidView(factory = { VideoView(it).apply { player = this; setVideoURI(Uri.parse("android.resource://${context.packageName}/$resource")); setOnPreparedListener { media -> media.isLooping = false; start() } } }, modifier = Modifier.fillMaxWidth().height(500.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) { Link("다시 보기", modifier = Modifier.weight(1f)) { player?.seekTo(0); player?.start() }; Action("닫기", modifier = Modifier.weight(1f), onClick = onClose) }
    } } }
}
