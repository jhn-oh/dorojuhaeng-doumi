package kr.juhyu.licensenote

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

internal fun reportText(s: PracticeSession): String {
    val assessment = PracticeEvaluator.evaluate(s)
    val score = assessment.score
    val duration = s.endedAtMs?.let { ((it - s.startedAtMs).coerceAtLeast(0) / 1000) }
    return buildString {
        appendLine("도로주행 도우미 · " + s.practiceRoute.name)
        if (duration != null) appendLine("연습 시간 " + duration / 60 + "분 " + duration % 60 + "초")
        if (score != null) appendLine("연습 점수 ${score}점 · 46개 감점 항목 중 ${assessment.checked}개 확인")
        else if (s.rulesVersion >= 2) appendLine("확인한 회전 ${assessment.checked}곳 · 숫자 점수 보류")
        else appendLine(assessment.title.replace("\n", " "))
        appendLine("공식 도로주행시험 점수가 아니에요.")
        appendLine(assessment.reason)
        if (!s.safetyIssue && !s.interrupted) {
            PracticeEvaluator.turnAssessments(s).forEach {
                appendLine(it.event.direction.label + " · " + it.reason)
            }
            if (assessment.mistake) append("다음 연습 · 주변을 확인하고 깜빡이를 미리 켜 보세요.")
        }
    }.trim()
}

internal fun encodeReportQr(text: String) = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1,
    mapOf(EncodeHintType.CHARACTER_SET to "UTF-8", EncodeHintType.MARGIN to 4))

internal class ReceiverApi(address: String, debug: Boolean) {
    private val base = URL(address.trim().trimEnd('/')).also {
        require(it.userInfo == null && it.ref == null && it.query == null && it.host.isNotBlank())
        require(it.protocol == "https" || (debug && it.protocol == "http" && it.host == "127.0.0.1"))
    }
    fun pair() = request("/v1/pair", "", JSONObject())
    fun action(pair: JSONObject, name: String, body: JSONObject? = null): JSONObject {
        val id = pair.getString("id")
        require(id.matches(Regex("[A-Za-z0-9_-]{32}")))
        return request("/v1/pair/$id/$name", pair.getString("car"), body)
    }
    private fun request(path: String, token: String, body: JSONObject?): JSONObject {
        val connection = URL(base.toExternalForm() + path).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 3500
            connection.readTimeout = 3500
            if (token.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            if (connection.responseCode !in 200..299) throw IOException("HTTP " + connection.responseCode)
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally { connection.disconnect() }
    }
}
