package kr.juhyu.licensenote

internal data class CourseLessonStep(val title: String, val body: String, val marker: Int)
internal data class LessonSource(val name: String, val url: String)

internal enum class ParkedCourseLesson(
    val title: String,
    val overview: String,
    val mapLabel: String,
    val route: PracticeRoute,
    val steps: List<CourseLessonStep>,
    val sources: List<LessonSource>,
) {
    UTURN(
        "일원터널 사거리에서 방향 바꾸기",
        "강남운전면허시험장 A코스의 정차 학습 예시예요. 현장 표지와 강사 검수는 확인 전이에요.",
        "일원터널 사거리 학습 구간",
        PracticeRoute("lesson-ilwon-uturn", "일원터널 사거리 학습 구간", "영동대로 남행", "영동대로 북행",
            points = listOf(
                GeoPoint(37.488285, 127.074314), GeoPoint(37.488080, 127.074420),
                GeoPoint(37.486978, 127.075027), GeoPoint(37.486712, 127.075176),
                GeoPoint(37.486340, 127.075377), GeoPoint(37.486138, 127.075446),
                GeoPoint(37.485988, 127.075513), GeoPoint(37.486077, 127.075675),
                GeoPoint(37.486196, 127.075612), GeoPoint(37.486387, 127.075518),
                GeoPoint(37.486753, 127.075355), GeoPoint(37.488310, 127.074541),
            ), turns = emptyList(), distance = 561),
        listOf(
            CourseLessonStep("다가가기 전에", "지도에서 남행과 북행 차로를 찾아보세요. 지도 계산상 약 562m 구간이에요. 현장 유턴 표지와 허용 시간·신호는 아직 확인하지 못했어요.", 0),
            CourseLessonStep("표지와 신호 확인", "점이 있는 곳은 지도에서 경로가 돌아가는 지점이에요. 현장에서는 유턴 표지와 노면표시, 보조표지의 시간·신호 조건을 함께 확인하세요.", 6),
            CourseLessonStep("다른 차와 보행자 살피기", "맞은편 차량과 횡단하는 보행자를 확인하세요. 다른 사람의 통행을 방해할 우려가 있다면 유턴을 시도하지 마세요.", 11),
        ),
        listOf(
            LessonSource("한국도로교통공단 · 강남 A코스 도해", "https://www.koroad.or.kr/main/board/9/87734/board_view.do"),
            LessonSource("한국도로교통공단 · A코스 영상", "https://www.youtube.com/watch?v=VKMmgEs_1u0"),
            LessonSource("생활법령정보 · 유턴 시 준수사항", "https://www.easylaw.go.kr/CSP/CnpClsMainBtr.laf?ccfNo=2&cciNo=1&cnpClsNo=6&csmSeq=684"),
        ),
    ),
    TRAFFIC(
        "세종대로 혼잡 기록 읽기",
        "서울시 2026년 11월 14일 14:55 소통현황에 수록된 사례예요.",
        "시청앞교차로 → 청계광장",
        PracticeRoute("lesson-sejong-traffic", "세종대로 북행", "시청앞교차로", "청계광장",
            points = listOf(
                GeoPoint(37.5649683843, 126.9771111380), GeoPoint(37.5651279336, 126.9771276195),
                GeoPoint(37.5652683750, 126.9771346338), GeoPoint(37.5654054152, 126.9771322331),
                GeoPoint(37.5656394466, 126.9771202099), GeoPoint(37.5669973201, 126.9770622407),
                GeoPoint(37.5671045176, 126.9770571241), GeoPoint(37.5675064469, 126.9770357843),
                GeoPoint(37.5689758850, 126.9769724776),
            ), turns = emptyList(), distance = 445),
        listOf(
            CourseLessonStep("어디가 막혔을까요?", "광화문·덕수궁 일대 평균은 시속 12km였어요. 이 중 시청앞교차로에서 청계광장까지 북행 445m 구간은 시속 10km, ‘정체’로 분류됐어요.", 0),
            CourseLessonStep("앞차가 멈췄을 때", "속도가 느려도 앞차와 간격을 두세요. 앞차가 움직이면 신호와 앞쪽 공간을 함께 확인하세요.", 4),
            CourseLessonStep("교차로가 막혔을 때", "초록불이어도 앞쪽 정체로 교차로 안에서 멈출 우려가 있다면 정지선 앞에서 기다리세요. 이 구간의 개별 측정 시각은 제공되지 않았어요.", 8),
        ),
        listOf(
            LessonSource("서울특별시 · 실시간 도시데이터", "https://www.data.go.kr/data/15146211/openapi.do"),
            LessonSource("서울특별시 · 교통 소통 정보", "https://data.seoul.go.kr/dataList/OA-13291/A/1/datasetView.do"),
            LessonSource("생활법령정보 · 교차로 통행 방법", "https://www.easylaw.go.kr/CSP/CnpClsMainBtr.laf?ccfNo=2&cciNo=1&cnpClsNo=6&csmSeq=684"),
        ),
    )
}
