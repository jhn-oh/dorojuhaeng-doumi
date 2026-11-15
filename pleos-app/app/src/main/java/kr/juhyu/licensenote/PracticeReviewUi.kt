package kr.juhyu.licensenote

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable internal fun PracticeReviewScreen(s: PracticeSession, wide: Boolean, saving: Boolean,
                                             c: PracticeCoordinator, notebook: Boolean = false) {
    val review = remember(s) { practiceReview(s) }
    val p = LocalPalette.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Copy(if (notebook) "오답노트" else "종합 연습 점수", if (wide) 48 else 38, p.navy, FontWeight.SemiBold)
            Copy(review.score?.let { "${it}점 / 100" } ?: "점수 보류", if (wide) 76 else 60, p.blue, FontWeight.Bold)
            Copy(review.explanation, 25, p.second)
            Copy("평가 ${review.checked.size}/4 · " + review.checked.joinToString(" · ").ifBlank { "평가 자료 없음" }, 24, p.second)
            if (review.unassessed.isNotEmpty()) Copy("미평가 · " + review.unassessed.joinToString(" · "), 22, p.muted)
            Copy("복습 항목 ${review.findings.size}건 · 오답노트에 자동 저장", 28, p.ink, FontWeight.Medium)
            if (review.findings.isEmpty()) Copy("확인한 기록에서 복습할 항목이 감지되지 않았어요. 미평가 항목은 잘했다고 계산하지 않아요.", 24, p.second)
            for (item in if (notebook) review.findings else review.findings.take(3)) {
                val elapsed = ((item.atMs-s.startedAtMs)/1000).coerceAtLeast(0)
                EvidenceCard("${elapsed/60}분 ${elapsed%60}초 · ${item.title} · −${item.penalty}점", item.detail + "\n다음 연습 · " + item.goal)
            }
            if (notebook) { PastDriveQuestions(s.id); PreviousPracticeList(s.id, c) }
        }
        Spacer(Modifier.height(12.dp))
        if (!notebook) Action("오답노트 보기", !saving, modifier = Modifier.fillMaxWidth()) { c.go(Page.NOTEBOOK) }
        else Action(if (review.goals.isEmpty()) "다음 연습 준비" else "오답 목표로 다음 연습", !saving, modifier = Modifier.fillMaxWidth()) {
            if (review.goals.isEmpty()) c.go(Page.BELT) else c.recommend()
        }
        Row(Modifier.fillMaxWidth()) {
            Link(if (notebook) "결과로 돌아가기" else "다음 연습 준비", !saving, Modifier.weight(1f)) {
                if (notebook) c.go(Page.RESULT) else c.go(Page.BELT)
            }
            Link("휴대폰으로 보내기", !saving, Modifier.weight(1f)) { c.go(Page.SHARE) }
        }
    }
}
