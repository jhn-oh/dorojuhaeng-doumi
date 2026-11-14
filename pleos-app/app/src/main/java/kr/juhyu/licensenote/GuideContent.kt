package kr.juhyu.licensenote

internal const val GUIDE_BELT_WARNING = "safety_belt_warning"
internal const val GUIDE_WASHER_WARNING = "safety_washer_warning"
internal const val GUIDE_LOW_BEAM = "guide_low_beam"
internal const val GUIDE_HIGH_BEAM = "guide_high_beam"

internal fun Page.hasGuideEntry() = this in setOf(Page.WELCOME, Page.BELT, Page.MIRROR, Page.DOORS, Page.COURSE, Page.RESULT, Page.NOTEBOOK)

internal enum class GuideSection(val label: String) {
    START("출발 준비"), WEATHER("비·야간"), INDICATORS("표시등·경고"), PARK("주차·정차")
}

enum class GuideTopic(val title: String, internal val section: GuideSection, val subtitle: String) {
    SPEED("계기판 읽기", GuideSection.START, "속도와 기어 표시"),
    SIGNAL("깜빡이 켜는 법", GuideSection.START, "왼쪽 · 오른쪽"),
    WIPER("와이퍼 한 번 쓰기", GuideSection.WEATHER, "MIST 표시"),
    BELT("안전벨트 경고 읽기", GuideSection.INDICATORS, "운전석 미착용"),
    WASHER("워셔액 경고 읽기", GuideSection.INDICATORS, "워셔액 부족"),
    MIRRORS("사이드미러 맞추기", GuideSection.START, "왼쪽 L · 오른쪽 R"),
    DOOR("문 열림 위치 찾기", GuideSection.START, "어느 문이 열렸을까?"),
    DEFOG("유리 습기 없애기", GuideSection.WEATHER, "앞유리 송풍 · 뒷유리 열선"),
    BEAMS("상향등과 하향등 쓰기", GuideSection.WEATHER, "마주 오는 차가 있을 때"),
    HAZARD("비상등 켜는 법", GuideSection.INDICATORS, "양쪽 화살표가 함께 깜빡일 때"),
    TIRE("타이어 경고등 읽기", GuideSection.INDICATORS, "계속 켜짐 · 깜빡인 뒤 켜짐"),
    PARKING_BRAKE("전자식 주차 브레이크", GuideSection.PARK, "EPB 스위치와 브레이크 경고등"),
    HOLD("오토홀드 표시 읽기", GuideSection.PARK, "대기 · 정차 유지 · 이상")
}

internal enum class GuideWarning(val label: String) {
    ON("경고 켜짐"), OFF("경고 꺼짐"), UNSUPPORTED("차량 미지원"), UNKNOWN("신호 확인 불가")
}

internal fun guideWarning(signal: Signal?, now: Long): GuideWarning {
    if (signal?.quality == "UNSUPPORTED") return GuideWarning.UNSUPPORTED
    if (!signal.fresh(now, 5000)) return GuideWarning.UNKNOWN
    return when (signal?.value) {
        "true" -> GuideWarning.ON
        "false" -> GuideWarning.OFF
        else -> GuideWarning.UNKNOWN
    }
}

internal fun guideBuckleLabel(signal: Signal?, now: Long): String = when (signal.takeIf { it.fresh(now) }?.value) {
    "true" -> "버클 채워짐"
    "false" -> "버클 풀림"
    else -> "버클 신호 확인 불가"
}

internal data class GuideStep(val title: String, val text: String)

internal val GuideTopic.examples: Pair<String, String>? get() = when (this) {
    GuideTopic.SIGNAL -> "왼쪽" to "오른쪽"
    GuideTopic.MIRRORS -> "왼쪽 L" to "오른쪽 R"
    GuideTopic.DEFOG -> "앞유리" to "뒷유리"
    GuideTopic.BEAMS -> "하향등" to "상향등"
    GuideTopic.TIRE -> "계속 켜짐" to "점멸 후 켜짐"
    GuideTopic.DOOR -> "운전석 문" to "오른쪽 뒷문"
    else -> null
}

internal fun doorName(key: String): String = when {
    key.endsWith("ROW_1_LEFT") -> "운전석 문"
    key.endsWith("ROW_1_RIGHT") -> "앞좌석 오른쪽 문"
    key.endsWith("ROW_2_LEFT") -> "뒷좌석 왼쪽 문"
    key.endsWith("ROW_2_RIGHT") -> "뒷좌석 오른쪽 문"
    key.endsWith("REAR") -> "트렁크"
    key.endsWith("HOOD") -> "보닛"
    else -> "차 문"
}

internal fun guideBeamLabel(signal: Signal?, now: Long): String {
    if (signal?.quality == "UNSUPPORTED") return "차량 미지원"
    return when (signal.takeIf { it.fresh(now, 5000) }?.value) {
        "ON" -> "켜짐"
        "OFF" -> "꺼짐"
        "DAYTIME_RUNNING" -> "주간주행등 상태"
        else -> "신호 확인 불가"
    }
}

private val cabinDoors = listOf("door:ROW_1_LEFT", "door:ROW_1_RIGHT", "door:ROW_2_LEFT", "door:ROW_2_RIGHT")

internal fun guideDoorSummary(signals: Map<String, Signal>, now: Long): String {
    val positions = cabinDoors.associateWith { key ->
        signals[key].takeIf { it.fresh(now) }?.value?.toIntOrNull()?.takeIf { it >= 0 }
    }
    val open = positions.filterValues { it != null && it > 0 }.keys
    if (open.isNotEmpty()) {
        val names = open.joinToString(", ", transform = ::doorName)
        return names + " 열림" + if (positions.containsValue(null)) " · 일부 신호 미확인" else ""
    }
    return if (positions.containsValue(null)) "좌석 문 신호 일부 또는 전체 미확인" else "앞·뒷좌석 문 4개 닫힘"
}

internal fun GuideTopic.steps(alternate: Boolean) = when (this) {
    GuideTopic.SPEED -> listOf(
        GuideStep("속도계를 찾아요", "계기판에서 km/h 표시를 찾으세요. 지금 차가 얼마나 빠르게 움직이는지 알려주는 곳이에요."),
        GuideStep("단위를 함께 읽어요", "예시 바늘이 0에서 40으로 움직여요. 40 km/h는 시속 40킬로미터라는 뜻이에요."),
        GuideStep("기어 표시를 구별해요", "D는 전진, P는 주차예요. 속도계가 0이어도 기어는 D일 수 있어요.")
    )
    GuideTopic.SIGNAL -> listOf(
        GuideStep("깜빡이 레버를 찾아요", "핸들 왼쪽 레버에서 화살표 표시를 찾으세요. 내 차의 조작 위치를 먼저 확인하세요."),
        GuideStep(if (alternate) "오른쪽은 레버를 위로" else "왼쪽은 레버를 아래로", "예시에서 레버를 움직이면 계기판의 ${if (alternate) "오른쪽" else "왼쪽"} 화살표가 깜빡여요."),
        GuideStep("화살표가 꺼졌는지 확인해요", "방향 전환을 마친 뒤에도 표시가 깜빡이면 레버를 가운데로 돌려 놓으세요.")
    )
    GuideTopic.WIPER -> listOf(
        GuideStep("한 번 닦는 표시를 찾아요", "앞유리 와이퍼 레버의 MIST 또는 1회 작동 표시를 찾으세요. 표기와 조작 방향은 차종에 따라 달라요."),
        GuideStep("살짝 움직였다 놓아요", "MIST 위치로 잠깐 움직였다 놓으면 앞유리 와이퍼가 한 번 왕복해요."),
        GuideStep("제자리로 돌아와요", "레버를 놓으면 OFF로 돌아오고, 한 번 닦은 와이퍼는 유리 아래쪽에서 멈춰요.")
    )
    GuideTopic.BELT -> listOf(
        GuideStep("벨트 그림을 찾아요", "사람 앞에 띠가 걸쳐진 모양이에요. 운전석 미착용 경고가 켜지면 벨트부터 확인하세요."),
        GuideStep("버클을 채워요", "벨트가 꼬이지 않게 펴고 금속 걸쇠를 버클에 끝까지 끼우세요."),
        GuideStep("착용 상태를 직접 확인해요", "벨트를 가볍게 당겨 잠겼는지 확인하세요. 경고가 꺼졌다는 표시만으로 착용 상태를 판단할 수 없어요.")
    )
    GuideTopic.WASHER -> listOf(
        GuideStep("워셔액 부족 표시를 읽어요", "앞유리에 물줄기가 퍼지는 그림이나 워셔액 부족 문구를 찾아보세요. 차량마다 표시 방식이 달라요."),
        GuideStep("어떤 액인지 구별해요", "워셔액은 앞유리를 닦을 때 분사하는 액체예요. 부족 경고는 남은 양이 적다는 뜻이에요."),
        GuideStep("내 차의 보충 위치를 확인해요", "안전하게 주차한 뒤 차량 취급설명서에서 워셔액 주입구 위치와 맞는 제품을 확인하세요.")
    )
    GuideTopic.MIRRORS -> listOf(
        GuideStep("주차하고 거울을 펼쳐요", "평소 운전 자세로 앉아 양쪽 거울을 확인하세요. 각도는 출발 전에 맞춰요."),
        GuideStep(if (alternate) "R로 오른쪽 거울을 골라요" else "L로 왼쪽 거울을 골라요",
            "조절할 쪽을 선택한 뒤 방향 스위치를 조금씩 눌러 옆과 뒤가 보이게 맞추세요."),
        GuideStep("뒤차와의 거리를 살펴요", "볼록거울에서는 뒤차가 실제보다 멀게 보일 수 있어요. 룸미러도 함께 확인하세요.")
    )
    GuideTopic.DOOR -> listOf(
        GuideStep("차 그림의 앞쪽을 찾아요", "이 예시는 위에서 내려다본 차예요. 차의 앞쪽과 좌우를 기준으로 열린 문을 찾아요."),
        GuideStep(if (alternate) "오른쪽 뒷문이 열렸어요" else "운전석 문이 열렸어요",
            "그림에서 차체 밖으로 벌어진 문을 보세요. 아래의 내 차 상태는 차량에서 읽은 좌석 문 정보예요."),
        GuideStep("열린 곳을 닫고 다시 확인해요", "출발 전에 모든 문과 보닛, 트렁크가 닫혔는지 직접 확인하고 계기판의 열림 표시도 살펴보세요.")
    )
    GuideTopic.DEFOG -> listOf(
        GuideStep("어느 유리가 흐린가요?", "앞유리 안쪽이 흐리면 앞유리 방향으로 바람을 보내요. 뒷유리에 낀 습기나 서리는 뒷유리 열선을 사용해요."),
        if (alternate) GuideStep("뒷유리는 열선으로 데워요", "시동이 걸린 상태에서 뒷유리 열선 버튼을 누르세요. 버튼의 표시등이 켜져요.")
        else GuideStep("앞유리에 바람을 보내요", "앞유리 서리 제거 버튼을 누르고 풍량을 조절하세요. 외기 유입과 에어컨이 자동으로 켜지지 않으면 직접 선택하세요."),
        if (alternate) GuideStep("깨끗해지면 열선을 꺼요", "버튼을 다시 누르면 꺼져요. 쏘나타 예시에서는 켜 둬도 약 20분 후 자동으로 꺼져요.")
        else GuideStep("시야를 확인해요", "유리 안쪽의 습기가 사라져 앞이 잘 보일 때 출발하세요.")
    )
    GuideTopic.BEAMS -> listOf(
        GuideStep("빛이 향하는 곳을 봐요", "하향등은 앞쪽 노면을 비추고, 상향등은 더 먼 곳까지 비춰요. 두 예시를 눌러 빛의 방향을 비교하세요."),
        if (alternate) GuideStep("앞차가 없는 어두운 길이에요", "전조등을 켠 상태에서 레버를 계기판 쪽으로 밀면 상향등이 켜져요. 계기판의 상향 표시등을 확인하세요.")
        else GuideStep("앞차가 있으면 하향등을 써요", "마주 오는 차나 앞서 가는 차의 운전자가 눈부실 수 있어요. 상향등이 켜져 있으면 하향등으로 바꾸세요."),
        GuideStep("상향 표시가 꺼졌는지 봐요", "쏘나타 예시에서는 켜 둔 상향등을 운전자 쪽으로 레버를 당겨 해제해요.")
    )
    GuideTopic.HAZARD -> listOf(
        GuideStep("삼각형 버튼을 찾아요", "차량 고장 같은 비상 상황을 주변에 알릴 때 쓰는 버튼이에요."),
        GuideStep("양쪽 화살표가 함께 깜빡여요", "비상등을 켜면 좌우 방향지시등이 동시에 깜빡여요. 한쪽만 켜지는 깜빡이와 비교해 보세요."),
        GuideStep("필요가 끝나면 다시 눌러요", "쏘나타에서는 비상등이 켜진 동안 레버로 한쪽 방향을 알릴 수 없어요. 비상 상황이 끝나면 비상등을 끄세요.")
    )
    GuideTopic.TIRE -> listOf(
        GuideStep("타이어 안 느낌표를 찾아요", "시동 직후의 짧은 점등을 지나서도 표시가 남으면, 계속 켜졌는지 깜빡인 뒤 켜졌는지 살펴보세요."),
        if (alternate) GuideStep("약 1분 깜빡인 뒤 켜져요", "이 패턴은 공기압 감지 시스템의 이상 표시예요. 정비소에 시스템 점검을 요청하세요.")
        else GuideStep("계속 켜지면 공기압을 확인해요", "타이어 공기압이 많이 낮을 수 있어요. 속도를 줄이고 안전한 곳에 정차해 타이어 상태를 확인하세요."),
        GuideStep("내 차 기준으로 확인해요", "타이어가 식었을 때 측정하세요. 쏘나타의 권장 공기압은 운전석 문을 열면 보이는 차체 기둥 라벨에 있어요.")
    )
    GuideTopic.PARKING_BRAKE -> listOf(
        GuideStep("완전히 멈춰요", "브레이크 페달을 밟아 차를 완전히 세우세요. 변속기의 P와 전자식 주차 브레이크는 각각 확인해요."),
        GuideStep("브레이크를 밟고 스위치를 당겨요", "쏘나타 EPB 스위치를 위로 당겨 체결하고 계기판의 브레이크 경고등이 켜졌는지 확인하세요."),
        GuideStep("차를 떠나기 전에 확인해요", "주차 브레이크 체결과 P단을 확인하고 차가 움직이지 않는지 살펴보세요. 해제 후에도 브레이크 경고등이 남으면 출발 전에 점검하세요.")
    )
    GuideTopic.HOLD -> listOf(
        GuideStep("흰색은 작동 대기예요", "브레이크를 밟고 AUTO HOLD 버튼을 누르면 흰색 표시가 켜져요. 이때는 브레이크 페달을 계속 밟으세요."),
        GuideStep("초록색은 정차를 유지하고 있어요", "브레이크를 밟아 완전히 멈춘 뒤 표시가 초록색으로 바뀌면 오토홀드가 제동을 유지해요."),
        GuideStep("노란색은 점검 표시예요", "노란 AUTO HOLD가 켜지면 브레이크 페달로 정차를 유지하고 점검을 받으세요. 주차할 때는 P단과 주차 브레이크를 확인하세요.")
    )
}
