package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test

class GuideTest {
    private val now = 20_000L

    @Test fun onlyFreshExplicitBooleansBecomeWarningStates() {
        fun signal(value: String?, time: Long = now, quality: String = "VALID") = Signal(value, quality, receivedMs = time)
        assertEquals(GuideWarning.ON, guideWarning(signal("true"), now))
        assertEquals(GuideWarning.OFF, guideWarning(signal("false", now - 5000), now))
        for (input in listOf(null, signal(null), signal("0"), signal("false", now - 5001),
            signal("true", now + 1), signal("false", quality = "ERROR"), signal(null, quality = "UNAVAILABLE"))) {
            assertEquals(GuideWarning.UNKNOWN, guideWarning(input, now))
        }
        assertEquals(GuideWarning.UNSUPPORTED, guideWarning(signal(null, quality = "UNSUPPORTED"), now))
    }

    @Test fun guideDoesNotOfferAnEntryDuringARecordedDrive() {
        assertFalse(Page.DRIVING.hasGuideEntry())
        assertTrue(Page.WELCOME.hasGuideEntry())
        assertTrue(Page.BELT.hasGuideEntry())
        assertTrue(Page.RESULT.hasGuideEntry())
    }

    @Test fun unbuckledSeatWithUnavailableWarningStaysTwoDistinctReadings() {
        val buckle = Signal("false", "VALID", receivedMs = now)
        val warning = Signal(quality = "UNSUPPORTED")
        assertEquals("버클 풀림", guideBuckleLabel(buckle, now))
        assertEquals(GuideWarning.UNSUPPORTED, guideWarning(warning, now))
        assertEquals("버클 신호 확인 불가", guideBuckleLabel(buckle, now + 2501))
    }

    @Test fun partialDoorDataDoesNotSayAllDoorsClosed() {
        val keys = listOf("door:ROW_1_LEFT", "door:ROW_1_RIGHT", "door:ROW_2_LEFT", "door:ROW_2_RIGHT")
        fun reading(value: String?, time: Long = now) = Signal(value, "VALID", receivedMs = time)
        val closed = keys.associateWith { reading("0") }
        assertEquals("앞·뒷좌석 문 4개 닫힘", guideDoorSummary(closed + ("door:REAR" to reading("1")), now))
        val stale = closed + (keys[0] to reading("0", now - 3000))
        assertEquals("좌석 문 신호 일부 또는 전체 미확인", guideDoorSummary(stale, now))
        assertEquals("뒷좌석 오른쪽 문 열림 · 일부 신호 미확인", guideDoorSummary(stale + (keys[3] to reading("1")), now))
        assertEquals("좌석 문 신호 일부 또는 전체 미확인", guideDoorSummary(closed + (keys[2] to reading("-1")), now))
    }

    @Test fun daytimeRunningAndMissingLightDataAreDifferentFromAnOffBeam() {
        fun light(value: String?, quality: String = "VALID") = Signal(value, quality, receivedMs = now)
        assertEquals("주간주행등 상태", guideBeamLabel(light("DAYTIME_RUNNING"), now))
        assertEquals("켜짐", guideBeamLabel(light("ON"), now))
        assertEquals("꺼짐", guideBeamLabel(light("OFF"), now))
        assertEquals("차량 미지원", guideBeamLabel(light(null, "UNSUPPORTED"), now))
        assertEquals("신호 확인 불가", guideBeamLabel(light("OFF"), now + 5001))
        assertEquals("신호 확인 불가", guideBeamLabel(light("UNKNOWN"), now))
    }

    @Test fun situationExamplesHaveDistinctInstructionsAndAnswerChoices() {
        for (topic in GuideTopic.entries) {
            val original = topic.steps(false)
            assertEquals(3, original.size)
            if (topic.examples != null) assertNotEquals(original[1], topic.steps(true)[1])
            val quiz = topic.question ?: continue
            assertFalse(topic.manualSection.isNullOrBlank())
            assertTrue(quiz.correct in 0..1)
            assertNotEquals(quiz.first, quiz.second)
            assertTrue(quiz.reason.isNotBlank())
        }
    }
}
