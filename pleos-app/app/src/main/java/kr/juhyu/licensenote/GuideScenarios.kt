package kr.juhyu.licensenote

internal data class GuideQuestion(
    val prompt: String,
    val first: String,
    val second: String,
    val correct: Int,
    val reason: String
)

internal val GuideTopic.question: GuideQuestion? get() = when (this) {
    GuideTopic.MIRRORS -> GuideQuestion("조수석 쪽 거울만 맞추려면?", "L 선택", "R 선택", 1,
        "R은 오른쪽 거울이에요. 주차 중에 R을 선택한 뒤 방향 스위치로 각도를 맞춰요.")
    GuideTopic.DOOR -> GuideQuestion("좌석 문 4개가 모두 닫혔다면 트렁크도 확인된 걸까요?", "트렁크는 따로 확인", "트렁크까지 확인됨", 0,
        "좌석 문 신호의 범위는 앞뒤 네 문이에요. 보닛과 트렁크는 각각 확인해야 해요.")
    GuideTopic.DEFOG -> GuideQuestion("비 오는 날 앞유리 안쪽에 김이 서렸어요. 어느 버튼을 쓸까요?", "뒷유리 열선", "앞유리 서리 제거", 1,
        "앞유리 쪽으로 바람을 보내고 외기 유입과 에어컨을 확인해요. 뒷유리 열선은 뒷유리를 데워요.")
    GuideTopic.BEAMS -> GuideQuestion("어두운 도로에서 맞은편 차가 다가와요. 어떤 등을 쓸까요?", "하향등", "상향등", 0,
        "빛이 상대 운전자의 눈을 향하지 않도록 하향등으로 바꿔요.")
    GuideTopic.HAZARD -> GuideQuestion("고장으로 비상등을 켜면 어떤 표시가 보일까요?", "왼쪽만 점멸", "양쪽 동시에 점멸", 1,
        "비상등은 모든 방향지시등을 함께 깜빡여 주변에 비상 상황을 알려요.")
    GuideTopic.TIRE -> GuideQuestion("타이어 표시가 약 1분 깜빡인 뒤 켜져 있어요. 무엇을 확인할까요?", "감지 시스템 점검", "공기만 보충", 0,
        "쏘나타에서 이 패턴은 감지 시스템의 이상 표시예요. 공기압과 별도로 시스템 점검이 필요해요.")
    GuideTopic.PARKING_BRAKE -> GuideQuestion("차를 주차하고 운전석을 떠나기 전에는?", "P단만 확인", "P단과 주차 브레이크 확인", 1,
        "P단, 주차 브레이크 체결, 차가 움직이지 않는지까지 확인하세요.")
    GuideTopic.HOLD -> GuideQuestion("AUTO HOLD가 흰색이에요. 지금 어떤 상태일까요?", "작동 대기", "정차 유지 중", 0,
        "흰색은 준비 상태예요. 브레이크를 밟아 완전히 정차한 뒤 초록색이 됐는지 확인해요.")
    else -> null
}

// The Korean 2026 SONATA (DN8) manual, by topic. Documentation links to these same sections.
internal val GuideTopic.manualSection: String? get() = when (this) {
    GuideTopic.MIRRORS -> "실외 미러"
    GuideTopic.DOOR -> "클러스터 표시창 정보"
    GuideTopic.DEFOG -> "유리창 습기 제거 · 뒷유리 열선"
    GuideTopic.BEAMS -> "전조등 상향, 하향 변환"
    GuideTopic.HAZARD -> "비상 경고등"
    GuideTopic.TIRE -> "저압 타이어 경고등 · 공기압 관리"
    GuideTopic.PARKING_BRAKE -> "전자식 파킹 브레이크"
    GuideTopic.HOLD -> "자동 정차 기능"
    else -> null
}
