package kr.juhyu.licensenote

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CoursePicker(ui: PracticeUi, wide: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current
    val recommendation = remember(ui.session) { CourseCatalog.recommendation(ui.session) }
    val lesson = when (ui.courseStep) {
        CourseStep.UTURN_LESSON -> ParkedCourseLesson.UTURN
        CourseStep.TRAFFIC_LESSON -> ParkedCourseLesson.TRAFFIC
        else -> null
    }
    if (lesson != null) {
        CourseLessonScreen(lesson, wide, c::back)
        return
    }
    val scroll = key(ui.courseStep) { rememberScrollState() }
    if (ui.courseStep == CourseStep.LIST) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(if (wide) 24.dp else 16.dp)) {
            Copy("어디서 연습할까요?", if (wide) 74 else 58, weight = FontWeight.SemiBold)
            Copy("시간과 연습할 동작을 보고 골라 보세요.", if (wide) 40 else 34, p.second)
            Spacer(Modifier.height(if (wide) 32.dp else 8.dp))
            recommendation?.let {
                val label = if (ui.session?.vehicleReview == true) "지난 깜빡이 기록 다시 연습" else "지난 오답 다시 연습"
                CourseCard(label, "${it.name} · ${it.stats}", Modifier.fillMaxWidth(), ui.selectedRouteId == it.id) { c.previewCourse(it) }
            }
            val choices = listOf("기본 도로주행" to CourseCatalog.full, "우회전 연습" to CourseCatalog.right,
                "유턴 연습" to null, "혼잡한 도로" to null, "좌회전 연습" to CourseCatalog.left, "차로 변경 연습" to CourseCatalog.lane)
            choices.chunked(if (wide) 2 else 1).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    pair.forEach { (title, route) ->
                        CourseCard(title, route?.stats ?: if (title == "유턴 연습") "일원터널 사거리 · 정차 중 학습" else "세종대로 · 혼잡 기록 학습", Modifier.weight(1f),
                            route != null && ui.selectedRouteId == route.id) {
                            if (route != null) c.previewCourse(route)
                            else c.courseStep(if (title == "유턴 연습") CourseStep.UTURN_LESSON else CourseStep.TRAFFIC_LESSON)
                        }
                    }
                }
            }
            Link("원하는 코스가 있나요?", modifier = Modifier.fillMaxWidth()) { c.courseStep(CourseStep.REQUEST) }
        }
        return
    }

    val destination = ui.courseStep == CourseStep.DESTINATION
    val matches = remember(ui.submittedRequest) { ui.submittedRequest?.let(::matchCourseRequest) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Copy("어떤 길을 연습할까요?", if (wide) 74 else 58, weight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Action("원하는 조건", secondary = destination, modifier = Modifier.weight(1f)) { c.courseStep(CourseStep.REQUEST) }
            Action("목적지 지정", secondary = !destination, modifier = Modifier.weight(1f)) { c.courseStep(CourseStep.DESTINATION) }
        }
        if (destination) {
            Copy("장소나 주소를 입력하세요.", 32, p.second)
            OutlinedTextField(value = ui.destinationQuery, onValueChange = { c.courseInput(it, true) }, singleLine = true,
                label = { Copy("목적지 검색", 26) }, modifier = Modifier.fillMaxWidth())
            val query = ui.destinationQuery.trim()
            if (ui.searchStarted > 0) {
                val found = ui.searchResult
                if (found != null) CourseCard("기본 내비에서 선택한 목적지", found.name, Modifier.fillMaxWidth(), true, c::previewNativeDestination)
                else Copy("기본 내비에서 장소를 고르고 안내를 시작한 뒤, 이 앱으로 돌아오세요.", 28, p.second)
                Link("검색 취소", modifier = Modifier.fillMaxWidth(), onClick = c::cancelNativeSearch)
            } else if (query.isNotEmpty()) Action("기본 내비에서 검색", modifier = Modifier.fillMaxWidth(), onClick = c::searchNativeDestination)
            val destinations = CourseCatalog.destinations(query)
            if (destinations.isNotEmpty()) Copy("등록된 연습 경로", 28, p.second)
            destinations.forEach { route ->
                CourseCard(route.destination, "${route.departure} 출발 · ${route.stats}", Modifier.fillMaxWidth(), route.id == ui.selectedRouteId) { c.previewCourse(route) }
            }
        } else {
            Copy("‘우회전만 연습하고 싶어’ 또는 ‘넓은 도로에서 고속도로 빼고 약 30분’처럼 요청해 보세요.", 31, p.second)
            OutlinedTextField(value = ui.courseRequest, onValueChange = { c.courseInput(it, false) },
                label = { Copy("원하는 연습 입력", 26) }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            SpeechInputButton("말로 요청하기", onTranscript = c::submitCourseRequest, modifier = Modifier.fillMaxWidth())
            Action("조건에 맞는 코스 찾기", modifier = Modifier.fillMaxWidth()) { c.submitCourseRequest(ui.courseRequest) }
            matches?.let { result ->
                if (result.message.isNotEmpty()) {
                    Copy(result.message, 29, p.second)
                    Link("요청을 지우고 다시 쓰기", modifier = Modifier.fillMaxWidth()) { c.courseInput("", false) }
                }
                result.routes.forEach { route ->
                    CourseCard(route.name, route.stats, Modifier.fillMaxWidth(), route.id == ui.selectedRouteId) { c.previewCourse(route) }
                }
            }
        }
    }
}

@Composable
private fun CourseCard(title: String, detail: String, modifier: Modifier, chosen: Boolean, click: () -> Unit) {
    val p = LocalPalette.current
    Surface(modifier = modifier.heightIn(min = 122.dp),
        color = if (chosen) p.tint else p.light, shape = RoundedCornerShape(22.dp),
        border = if (chosen) BorderStroke(2.dp, p.blue) else null) {
        Row(Modifier.fillMaxWidth().heightIn(min = 122.dp)
            .clickable(role = Role.Button, onClick = click)
            .semantics { if (chosen) stateDescription = "선택한 코스" }
            .padding(24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Copy(title, 35, if (chosen) p.blue else p.ink, FontWeight.SemiBold)
                Copy(detail, 28, p.second)
            }
            FigmaIcon(R.drawable.figma_back, 41, p.second, assetOverride = "course_chevron")
        }
    }
}
