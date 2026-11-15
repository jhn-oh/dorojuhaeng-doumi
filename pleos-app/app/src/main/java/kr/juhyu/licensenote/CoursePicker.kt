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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun CoursePicker(ui: PracticeUi, wide: Boolean, c: PracticeCoordinator) {
    val p = LocalPalette.current
    val destination = ui.courseStep == CourseStep.DESTINATION
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Copy("현재 위치에서 한 바퀴", if (wide) 74 else 58, weight = FontWeight.SemiBold)
        Copy("내 주변 도로를 달리고, 지금 출발하는 곳으로 돌아와요.", if (wide) 36 else 30, p.second)
        val goals = remember(ui.session) { ui.session?.takeIf { it.rulesVersion >= PRACTICE_SCORE_RULES }?.let { practiceReview(it).goals }.orEmpty() }
        if (goals.isNotEmpty()) EvidenceCard("지난 오답을 반영한 이번 연습 목표", goals.joinToString("\n"))
        if (ui.routeLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Copy(ui.routeMessage ?: "주변 도로를 찾고 있어요.", 30, p.blue)
        } else if (ui.courseStep == CourseStep.LIST) {
            Copy("어느 정도 연습할까요?", 34, weight = FontWeight.SemiBold)
            listOf(10, 20, 30).forEach { minutes ->
                CourseCard("약 ${minutes}분", "현재 위치 출발 · 한 바퀴 · 출발 위치로 복귀", Modifier.fillMaxWidth()) { c.planLocalLoop(minutes) }
            }
            Copy("실제 도로를 찾은 뒤 예상 거리와 시간을 보여드려요. 위치 수신과 네트워크 연결이 필요해요.", 26, p.second)
            Link("시간을 직접 정하기", modifier = Modifier.fillMaxWidth()) { c.courseStep(CourseStep.REQUEST) }
            Link("들를 장소 정하기", modifier = Modifier.fillMaxWidth()) { c.courseStep(CourseStep.DESTINATION) }
        } else if (destination) {
            Copy("들를 장소를 고르면 현재 위치에서 다녀오는 길을 찾아요.", 30, p.second)
            OutlinedTextField(value = ui.destinationQuery, onValueChange = { c.courseInput(it, true) }, singleLine = true,
                label = { Copy("경유할 장소나 주소", 26) }, modifier = Modifier.fillMaxWidth())
            if (ui.searchStarted > 0) {
                ui.searchResult?.let { found ->
                    CourseCard("${found.name} 다녀오기", "현재 위치 → 선택한 장소 → 출발 위치", Modifier.fillMaxWidth(), c::previewNativeDestination)
                } ?: Copy("기본 내비에서 장소를 고르고 안내를 시작한 뒤, 이 앱으로 돌아오세요.", 28, p.second)
                Link("검색 취소", modifier = Modifier.fillMaxWidth(), onClick = c::cancelNativeSearch)
            } else Action("기본 내비에서 장소 검색", enabled = ui.destinationQuery.isNotBlank(), modifier = Modifier.fillMaxWidth(), onClick = c::searchNativeDestination)
        } else {
            Copy("‘15분 정도 한 바퀴’처럼 시간을 말해 보세요.", 30, p.second)
            OutlinedTextField(value = ui.courseRequest, onValueChange = { c.courseInput(it, false) },
                label = { Copy("연습 시간 · 5~45분", 26) }, modifier = Modifier.fillMaxWidth(), maxLines = 2)
            SpeechInputButton("말로 요청하기", onTranscript = c::submitCourseRequest, modifier = Modifier.fillMaxWidth())
            Action("현재 위치에서 코스 찾기", enabled = ui.courseRequest.isNotBlank(), modifier = Modifier.fillMaxWidth()) { c.submitCourseRequest(ui.courseRequest) }
        }
    }
}

@Composable
private fun CourseCard(title: String, detail: String, modifier: Modifier, click: () -> Unit) {
    val p = LocalPalette.current
    Surface(modifier = modifier.heightIn(min = 122.dp), color = p.light, shape = RoundedCornerShape(22.dp), border = BorderStroke(1.dp, p.line)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 122.dp).clickable(role = Role.Button, onClick = click).padding(24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Copy(title, 35, p.ink, FontWeight.SemiBold)
                Copy(detail, 28, p.second)
            }
            FigmaIcon(R.drawable.figma_back, 41, p.second, assetOverride = "course_chevron")
        }
    }
}
