package kr.juhyu.licensenote

import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

internal data class HandbookAnswer(val topic: GuideTopic, val text: String)

/** Explicit control operations take priority over the character-pair fallback for handbook topics. */
internal object GuideAnswers {
    private data class Entry(val topic: GuideTopic, val phrases: String, val answer: String)
    private val entries = listOf(
        Entry(GuideTopic.WIPER, "와이퍼|와이퍼 한 번|와이퍼 어떻게 켜|비가 와서 앞유리를 한번 닦고 싶어|앞유리 닦는 법",
            "한 번 닦는 기능은 MIST 또는 1회 표시예요. 레버 위치와 조작 방향은 차종마다 달라요."),
        Entry(GuideTopic.WASHER, "워셔액|워셔액 부족|워셔액 경고등|워셔액이 부족하면|앞유리에 뿌리는 물",
            "워셔액은 앞유리를 닦을 때 쓰는 액체예요. 안전하게 주차한 뒤 내 차 설명서에서 주입구를 확인하세요."),
        Entry(GuideTopic.BELT, "안전벨트|안전띠|벨트 경고등|벨트 착용 경고|안전띠 어떻게 매",
            "벨트가 꼬이지 않게 펴고 걸쇠를 버클에 끝까지 끼우세요. 가볍게 당겨 잠겼는지 확인하세요."),
        Entry(GuideTopic.SIGNAL, "깜빡이|깜박이|방향지시등|깜빡이는 어떻게 켜|방향지시등 어떻게 쓰나요|차로 변경 전에 뭐부터 확인해|차선 변경할 때|좌회전할 때 깜빡이|왼쪽 깜빡이 켜고 싶은데 어떻게 켜면 돼|오른쪽 깜빡이 어떻게 켜",
            "설명서 기준으로 방향지시등 레버는 핸들 왼쪽에 있어요. 왼쪽은 레버를 아래로, 오른쪽은 위로 조작하세요. 계기판의 화살표가 깜빡이는지 확인하세요. 차종에 따라 조작 방법은 다를 수 있어요."),
        Entry(GuideTopic.SPEED, "속도계|계기판 속도|킬로미터 표시|기어 표시|기어 D랑 P",
            "출발 전 속도와 기어, 연료나 배터리 잔량을 확인하세요. 주행 중에는 도로 제한속도와 계기판 속도를 함께 확인하세요."),
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
    private val signalNames = listOf("깜빡이", "깜박이", "깜빡등", "깜박등", "방향지시등", "방향등", "턴시그널")

    private fun signalAnswer(words: String): String? {
        // A named control must not drift into another topic or answer a different operation.
        if (entries.any { it.topic != GuideTopic.SIGNAL && normalize(it.phrases.substringBefore('|')) in words }) return null
        if (listOf("왜", "언제", "몇", "색", "원리", "뜻").any { it in words }) return null
        val cancel = listOf("끄", "꺼", "취소", "해제").any { it in words }
        val location = listOf("어디", "위치", "어느쪽").any { it in words }
        if (!cancel && !location && signalNames.none { it == words } &&
            listOf("켜", "키는", "키려", "키나", "어떻게", "방법", "작동", "사용", "조작").none { it in words }) return null
        // The left-hand stalk can operate the right signal; its location is not the requested direction.
        val direction = words.replace("핸들왼쪽", "").replace("운전대왼쪽", "")
        val left = listOf("왼쪽", "좌측", "좌회전").any { it in direction }
        val right = listOf("오른쪽", "우측", "우회전").any { it in direction }
        val side = if (left != right) if (left) "왼쪽" else "오른쪽" else "해당 방향"
        // https://ownersmanual.hyundai.com/full_webhelp/DN8/2026/ko_KR/ideb4a90bce9f.html
        val instruction = when {
            cancel -> "깜빡이를 끄려면 핸들 왼쪽 레버를 가운데 위치로 돌려 놓으세요. 계기판의 $side 화살표가 꺼졌는지 확인하세요."
            left != right -> "$side 깜빡이를 켜려면 핸들 왼쪽 레버를 ${if (left) "아래로 내리세요" else "위로 올리세요"}. 계기판의 $side 화살표가 깜빡이는지 확인하세요."
            location -> "깜빡이 레버는 핸들 왼쪽에 있어요. 왼쪽 신호는 레버를 아래로, 오른쪽 신호는 위로 움직이세요."
            else -> "핸들 왼쪽 레버를 아래로 내리면 왼쪽, 위로 올리면 오른쪽 깜빡이가 켜져요. 계기판의 해당 방향 화살표를 확인하세요."
        }
        return "$instruction 쏘나타 설명서의 레버식 조작 기준이에요."
    }
    private fun features(text: String) = normalize(text).windowed(2).toSet()
    private val examples = entries.flatMap { entry -> entry.phrases.split('|').map { features(it) to entry } }
    private val weights = examples.flatMap { it.first }.groupingBy { it }.eachCount()
        .mapValues { (_, count) -> 1 + ln((examples.size + 1.0) / (count + 1.0)) }

    fun find(question: String): HandbookAnswer? {
        // Matched nouns alone must not answer a request to make a live safety decision.
        val words = normalize(question)
        if (listOf("지금가", "지금들어", "지금바꿔", "지금변경", "지금돌", "가도돼", "해도돼", "해도되", "괜찮",
                "긴급", "사고", "고장", "급발진", "불이났", "연기가", "안돼", "안되", "안들", "안먹", "안멈", "멈추지",
                "안꺼", "안켜", "켜지지", "꺼지지", "작동하지", "끼어들어도",
                "수리", "비용", "가격", "견적", "구매", "교체").any { it in words }) return null
        if (signalNames.any { it in words }) return signalAnswer(words)?.let { HandbookAnswer(GuideTopic.SIGNAL, it) }
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
