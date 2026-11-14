package kr.juhyu.licensenote

import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

internal data class HandbookAnswer(val topic: GuideTopic, val text: String)

/** A local nearest-neighbour intent classifier. Character pairs tolerate Korean particles and spacing. */
internal object GuideAnswers {
    private data class Entry(val topic: GuideTopic, val phrases: String, val answer: String)
    private val entries = listOf(
        Entry(GuideTopic.WIPER, "와이퍼|와이퍼 한 번|와이퍼 어떻게 켜|비가 와서 앞유리를 한번 닦고 싶어|앞유리 닦는 법",
            "한 번 닦는 기능은 MIST 또는 1회 표시예요. 레버 위치와 조작 방향은 차종마다 달라요."),
        Entry(GuideTopic.WASHER, "워셔액|워셔액 부족|워셔액 경고등|워셔액이 부족하면|앞유리에 뿌리는 물",
            "워셔액은 앞유리를 닦을 때 쓰는 액체예요. 안전하게 주차한 뒤 내 차 설명서에서 주입구를 확인하세요."),
        Entry(GuideTopic.BELT, "안전벨트|안전띠|벨트 경고등|벨트 착용 경고|안전띠 어떻게 매",
            "벨트가 꼬이지 않게 펴고 걸쇠를 버클에 끝까지 끼우세요. 가볍게 당겨 잠겼는지 확인하세요."),
        Entry(GuideTopic.SIGNAL, "깜빡이|깜박이|방향지시등|깜빡이는 어떻게 켜|방향지시등 어떻게 쓰나요|차로 변경 전에 뭐부터 확인해|차선 변경할 때|좌회전할 때 깜빡이",
            "옮겨갈 방향을 깜빡이로 알리세요. 거울과 사각지대를 직접 확인한 뒤 충분한 공간이 있을 때 움직이세요."),
        Entry(GuideTopic.SPEED, "속도계|계기판 속도|킬로미터 표시|기어 표시|기어 D랑 P",
            "계기판의 km/h는 시속이에요. 기어 표시에서 D는 전진, P는 주차를 뜻해요."),
        Entry(GuideTopic.MIRRORS, "사이드미러|사이드 미러 조절|거울 각도|옆 거울|실외 미러|뒷차가 보이게 거울",
            "거울은 주차 중에 맞추세요. 평소 운전 자세로 앉아 양쪽 거울과 룸미러로 옆과 뒤가 보이는지 확인하세요."),
        Entry(GuideTopic.DOOR, "문 열림|차 문이 열렸어|어느 문이 열린 거야|트렁크 열린 표시",
            "계기판 차 그림에서 열린 위치를 확인하세요. 안전하게 정차한 뒤 문과 트렁크가 닫혔는지 직접 확인하세요."),
        Entry(GuideTopic.DEFOG, "앞유리 습기|유리에 김이 서려|앞유리 성에|뒷유리 열선|유리 김서림 없애기",
            "앞유리 안쪽의 습기는 앞유리 방향 송풍으로 제거해요. 뒷유리의 습기는 뒷유리 열선을 사용해요."),
        Entry(GuideTopic.BEAMS, "상향등|하향등|상향등과 하향등 차이|앞차가 눈부셔|전조등 불빛",
            "하향등은 앞쪽 노면을, 상향등은 더 먼 곳을 비춰요. 마주 오는 차나 앞차가 있으면 하향등을 쓰세요."),
        Entry(GuideTopic.HAZARD, "비상등|비상 경고등|삼각형 버튼|양쪽 화살표가 깜빡여",
            "비상등은 차량 고장 같은 상황을 주변에 알리는 기능이에요. 켜면 양쪽 방향지시등이 함께 깜빡여요."),
        Entry(GuideTopic.TIRE, "타이어 경고등|공기압 경고등|타이어 느낌표|공기압이 낮아|타이어 점검",
            "공기압 경고가 계속 켜져 있으면 속도를 줄이고 안전한 곳에 정차해 타이어 상태를 확인하세요."),
        Entry(GuideTopic.PARKING_BRAKE, "주차 브레이크|전자식 파킹 브레이크|EPB|파킹브레이크 확인",
            "완전히 정차한 뒤 주차 브레이크 체결과 P단을 각각 확인하세요. 차량별 스위치 위치는 주차 후 확인하세요."),
        Entry(GuideTopic.HOLD, "오토홀드|오토 홀드 초록색|오토홀드 하얀색|AUTO HOLD|자동 정차 유지",
            "오토홀드의 흰색은 대기, 초록색은 정차 유지 표시예요. 주차할 때는 P단과 주차 브레이크를 확인하세요.")
    )
    private fun normalize(text: String) = text.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    private fun features(text: String) = normalize(text).windowed(2).toSet()
    private val examples = entries.flatMap { entry -> entry.phrases.split('|').map { features(it) to entry } }
    private val weights = examples.flatMap { it.first }.groupingBy { it }.eachCount()
        .mapValues { (_, count) -> 1 + ln((examples.size + 1.0) / (count + 1.0)) }

    fun find(question: String): HandbookAnswer? {
        // Matched nouns alone must not answer a request to make a live safety decision.
        val words = normalize(question)
        if (listOf("지금가", "지금들어", "지금바꿔", "지금변경", "지금돌", "가도돼", "해도돼", "해도되", "괜찮",
                "긴급", "사고", "고장", "급발진", "불이났", "연기가", "안돼", "안되", "안들", "안먹", "안멈", "멈추지",
                "수리", "비용", "가격", "견적", "구매", "교체").any { it in words }) return null
        val query = features(question.take(300))
        if (query.isEmpty()) return null
        fun squaredWeight(term: String): Double = (weights[term] ?: 3.0).let { it * it }
        val queryLength = sqrt(query.sumOf(::squaredWeight))
        val ranked = examples.groupBy { it.second }.map { (entry, documents) ->
            entry to documents.maxOf { (terms, _) ->
                terms.intersect(query).sumOf(::squaredWeight) / (queryLength * sqrt(terms.sumOf(::squaredWeight)))
            }
        }.sortedByDescending { it.second }
        val (best, score) = ranked.first()
        if (score < 0.37 || score - ranked[1].second < 0.11) return null
        if (best.topic == GuideTopic.PARKING_BRAKE && listOf("주차", "파킹", "epb").none { it in words }) return null
        return HandbookAnswer(best.topic, best.answer)
    }
}

internal fun wakeQuestion(transcript: String): String? {
    val match = Regex("^\\s*도움\\s*(?:에이\\s*아이|AI)[\\s,!.?]*", RegexOption.IGNORE_CASE).find(transcript) ?: return null
    return transcript.substring(match.range.last + 1).trim()
}
