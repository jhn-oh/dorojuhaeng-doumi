package kr.juhyu.licensenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CourseLessonScreen(lesson: ParkedCourseLesson, wide: Boolean, close: () -> Unit) {
    val colors = LocalPalette.current
    val uri = LocalUriHandler.current
    var index by rememberSaveable(lesson.name) { mutableIntStateOf(0) }
    var expanded by rememberSaveable(lesson.name) { mutableStateOf(false) }
    var linkFailed by remember { mutableStateOf(false) }
    val step = lesson.steps[index]
    val scroll = key(lesson.name, index) { rememberScrollState() }
    val detail: @Composable ColumnScope.() -> Unit = {
        Copy("${index + 1} / ${lesson.steps.size}", 26, colors.blue, FontWeight.SemiBold)
        Copy(step.title, if (wide) 47 else 39, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Copy(step.body, if (wide) 32 else 29, colors.second)
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Copy(lesson.title, if (wide) 57 else 44, weight = FontWeight.SemiBold)
            Copy(lesson.overview, if (wide) 31 else 27, colors.second)
            val map: @Composable (Modifier) -> Unit = { modifier ->
                CourseMap(null, modifier, route = lesson.route, mapLabel = lesson.mapLabel, focusPoint = lesson.route.points[step.marker])
            }
            if (wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                map(Modifier.weight(1f).height(400.dp))
                Column(Modifier.weight(1f), content = detail)
            } else {
                map(Modifier.fillMaxWidth().height(320.dp))
                detail()
            }
            Link(if (expanded) "자료 닫기" else "근거 자료 보기", modifier = Modifier.fillMaxWidth()) { expanded = !expanded; linkFailed = false }
            if (expanded) {
                Copy(if (lesson == ParkedCourseLesson.UTURN)
                    "공단 도해는 2023년 3월 8일 게시됐어요. 2026년 11월 14일 같은 OpenStreetMap 도로로 계산한 거리는 OSRM 561.2m, Valhalla 562m였어요. 현장 허용 조건과 강사 검수는 확인이 필요해요."
                else "서울시 도시데이터의 세종대로 링크 1010018100이에요. 지역 발표 시각은 14:55, 자료 조회 시각은 14:57이에요. 지금 이 도로의 교통 상태는 별도로 확인하세요.", 26, colors.second)
                if (lesson == ParkedCourseLesson.TRAFFIC) {
                    Copy("강남 연습 코스의 조회 기록", 29, weight = FontWeight.SemiBold)
                    Copy("같은 날 15:07에 강남역 동쪽~올림픽공원 서쪽 코스를 따로 조회했어요. 42개 표준링크 중 41개에 대응하는 17개 교통 구간의 속도는 시속 8.8~36.7km였어요. 구간별 측정 시각과 혼잡 등급은 제공되지 않았어요.", 26, colors.second)
                }
                lesson.sources.forEach { source ->
                    Link(source.name, modifier = Modifier.fillMaxWidth()) {
                        linkFailed = runCatching { uri.openUri(source.url) }.isFailure
                    }
                }
                if (linkFailed) Copy("자료를 여는 앱을 찾지 못했어요.", 25, colors.second)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Action("이전", enabled = index > 0, secondary = true, modifier = Modifier.weight(1f)) { index--; expanded = false }
            Action(if (index == lesson.steps.lastIndex) "코스 목록으로" else "다음 단계", modifier = Modifier.weight(1.5f)) {
                if (index == lesson.steps.lastIndex) close() else { index++; expanded = false }
            }
        }
    }
}
