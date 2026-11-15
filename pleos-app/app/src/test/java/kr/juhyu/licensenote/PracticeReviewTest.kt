package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test

class PracticeReviewTest {
    private fun session(belt: String = "true", door: String = "0", turn: String = "OFF", bend: Boolean = false): PracticeSession {
        val samples = (0..20).flatMap { second ->
            val at = 1000L+second*1000
            fun row(key: String, value: String) = RecordedSignal(at,key,value,"VALID","test","CALLBACK")
            val h = if (bend && second >= 8) 270 else 0
            listOf(row("speed","5"),row("gear","8"),row("belt",belt),row("door:ROW_1_LEFT",door),row("turn",turn),
                row("steering",if (bend && second in 5..9) "40" else "0"),
                row("location","${37.4+second*.000025},127.1,$h,5,${100000+second*1000}"))
        }
        return PracticeSession(rulesVersion=PRACTICE_SCORE_RULES,startedAtMs=1000,startedWallMs=1000,
            endedAtMs=21000,phase=SessionPhase.COMPLETE,expectedDoors=listOf("door:ROW_1_LEFT"),samples=samples)
    }
    @Test fun continuousValidBasicReadingsHaveAnExplicitlyPartialScore() {
        val r=practiceReview(session())
        assertEquals(100,r.score);assertEquals(3,r.checked.size);assertTrue(r.unassessed.contains("회전 깜빡이"))
        assertTrue(r.findings.isEmpty())
    }
    @Test fun unbuckledAndOpenDoorAreSavedOnceAndBecomeNextPracticeGoals() {
        val r=practiceReview(session("false","1"))
        assertEquals(60,r.score);assertEquals(2,r.findings.size);assertEquals(2,r.goals.size)
        assertEquals(60,PracticeEvaluator.evaluate(session("false","1")).score)
    }
    @Test fun noDataStationaryAndInterruptedSessionsCannotBecomePerfectScores() {
        val s=session()
        assertNull(practiceReview(s.copy(samples=emptyList())).score)
        assertNull(practiceReview(s.copy(samples=s.samples.map { if(it.key=="speed") it.copy(value="0") else it })).score)
        assertNull(practiceReview(s.copy(interrupted=true)).score)
        assertNull(practiceReview(s.copy(safetyIssue=true)).score)
        assertNull(practiceReview(s.copy(samples=s.samples.filterNot { it.atMs in 8000..16000 })).score)
    }
    @Test fun dynamicTurnRequiresSteeringAndSignalCoverageAndIsNotADuplicate() {
        val r=practiceReview(session(bend=true))
        assertEquals(90,r.score);assertEquals(1,r.findings.count { it.key=="signal-left" })
        assertTrue(r.checked.contains("회전 깜빡이"))
        assertTrue(practiceReview(session(turn="LEFT",bend=true)).findings.isEmpty())
        val missing=session(bend=true).copy(samples=session(bend=true).samples.filterNot { it.key=="steering" })
        assertFalse(practiceReview(missing).checked.contains("회전 깜빡이"))
    }
    @Test fun missingBeltAndDoorSignalsAreUnassessedInsteadOfAnAutomaticPass() {
        val s=session().let { it.copy(samples=it.samples.filterNot { row -> row.key=="belt" || row.key.startsWith("door:") }) }
        val r=practiceReview(s)
        assertNull(r.score);assertTrue(r.unassessed.containsAll(listOf("벨트","문")))
    }
    @Test fun exportedSummaryContainsTheSameScoreAndAutomaticMistakes() {
        val text=reportText(session("false","1"))
        assertTrue(text.contains("60점"));assertTrue(text.contains("벨트"));assertTrue(text.contains("문 열림"))
        assertTrue(text.contains("다음 목표"))
    }
}
