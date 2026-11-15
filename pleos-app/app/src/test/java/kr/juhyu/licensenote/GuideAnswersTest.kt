package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test

class GuideAnswersTest {
    @Test fun leftSignalOperationSurvivesNaturalPhrasingAndNames() {
        for (question in listOf(
            "왼쪽 깜빡이를 어떻게 켜?",
            "왼쪽 방향 지시등을 켜려면 레버를 어디로 움직여야 하나요?",
            "좌측 깜박등 좀 켜는 방법 알려 줘",
            "왼쪽 턴 시그널은 어떻게 켜나요"
        )) {
            val answer = GuideAnswers.find(question)
            assertEquals(question, GuideTopic.SIGNAL, answer?.topic)
            assertTrue(question, answer!!.text.contains("아래로 내리세요"))
            assertFalse(question, answer.text.contains("와이퍼"))
        }
    }

    @Test fun requestedSignalDirectionIsSeparateFromTheStalkLocation() {
        val answer = GuideAnswers.find("핸들 왼쪽 레버로 오른쪽 깜빡이 어떻게 켜?")!!
        assertTrue(answer.text.contains("위로 올리세요"))
        assertTrue(answer.text.contains("오른쪽 화살표"))
        assertFalse(answer.text.contains("아래로"))
    }

    @Test fun turningOffExplainsCancellationRatherThanTurningOn() {
        for (question in listOf("왼쪽 깜빡이는 어떻게 꺼요?", "우측 방향지시등 끄는 방법")) {
            val answer = GuideAnswers.find(question)
            assertEquals(question, GuideTopic.SIGNAL, answer?.topic)
            assertTrue(question, answer!!.text.contains("가운데"))
            assertTrue(question, answer.text.contains("꺼졌는지"))
            assertFalse(question, answer.text.contains("아래로 내리세요"))
            assertFalse(question, answer.text.contains("위로 올리세요"))
        }
    }

    @Test fun explicitSignalDoesNotRouteAnUnsupportedRequestToAnotherControl() {
        for (question in listOf(
            "왼쪽 깜빡이 켜고 지금 끼어들어도 돼?",
            "왼쪽 깜빡이가 안 꺼져",
            "오른쪽 깜빡이가 켜지지 않아",
            "깜빡이와 와이퍼는 어떻게 켜요?",
            "깜빡이는 왜 초록색이야?"
        )) assertNull(question, GuideAnswers.find(question))
        assertEquals(GuideTopic.WIPER, GuideAnswers.find("와이퍼 한 번")?.topic)
        assertEquals(GuideTopic.HAZARD, GuideAnswers.find("비상등")?.topic)
    }
}
