package kr.juhyu.licensenote

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.junit.Assert.*
import org.junit.Test

class ReportHandoffTest {
    @Test fun offlineQrContainsThisSessionsKoreanSummaryWithoutPreciseLocation() {
        val s = PracticeSession(startedAtMs = 1000, startedWallMs = 1, endedAtMs = 66000,
            rulesVersion = 2, phase = SessionPhase.COMPLETE, expectedDoors = emptyList())
        val summary = reportText(s)
        assertTrue(summary.contains("1분 5초"))
        assertTrue(summary.contains("숫자 점수 보류"))
        assertTrue(summary.contains("공식 도로주행시험 점수가 아니에요"))
        assertFalse(summary.contains(s.id))
        val matrix = encodeReportQr(summary)
        val width = matrix.width * 5
        val pixels = IntArray(width * width) { i -> if (matrix[(i % width) / 5, (i / width) / 5]) 0xff000000.toInt() else -1 }
        val decoded = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(width, width, pixels))))
        assertEquals(summary, decoded.text)
    }
}
