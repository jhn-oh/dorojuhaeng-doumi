package kr.juhyu.licensenote

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechWordsTest {
    @Test fun abbreviationsAreSpokenInKoreanWithoutChangingOrdinarySentences() {
        assertEquals("미스트 또는 1회 표시예요. 피 단, 전자식 주차 브레이크를 확인하세요.",
            speechWords("MIST 또는 1회 표시예요. P단·EPB를 확인하세요."))
        assertEquals("좌회전 전에 깜빡이를 확인하세요.", speechWords("좌회전 전에 깜빡이를 확인하세요."))
    }
}
