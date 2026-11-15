package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test

class NativeLoopGuidanceTest {
    @Test fun liveNativeGuidanceRecoversACancelledRequestWithoutTheIsRoutingFlag() {
        assertNull(observedCourseNavigationState(false, false, false, false))
        assertEquals("GUIDING_OTHER_ROUTE", observedCourseNavigationState(false, true, false, false))
        assertEquals("GUIDING_UNCONFIRMED", observedCourseNavigationState(false, true, true, false))
        assertEquals("ROUTE_READY", observedCourseNavigationState(false, true, true, true))
        // A genuine cancellation stays inactive instead of being turned into a successful route.
        assertNull(observedCourseNavigationState(false, false, false, false))
    }

    private val now = 20_000L
    private val point = GeoPoint(37.41272, 127.09468)
    private val loop = PracticeRoute("local-loop-test", "주변 한 바퀴", "현재 위치", "현재 위치",
        listOf(point, GeoPoint(37.42, 127.10), point), emptyList(), origin = point)
    private val session = PracticeSession(courseId = loop.id, plannedRoute = loop, startedAtMs = now - 1000,
        startedWallMs = 1000, expectedDoors = emptyList())
    private fun signal(value: String) = Signal(value, "VALID", "test", now, "CALLBACK", sampleNs = now * 1_000_000)
    private fun signals(mode: String = "ROUTE_GUIDING") = mapOf(
        "location" to signal("${point.lat},${point.lon},0,5,1000"),
        "navigation_location" to signal("${point.lat},${point.lon},0"),
        "navigation_route" to signal("false,$mode"),
        "native_course_tbt" to signal("${loop.id}|TURN_LEFT|80.0|기업지원허브 방면"))

    @Test fun currentNativeLoopGuidanceDoesNotRequireAScoredPracticeCornerOrRoutingFlag() {
        val cue = driveCue(session, signals(), now)
        assertEquals("기본 내비 안내", cue.source)
        assertEquals("LEFT", cue.turnKey)
        assertEquals(80, cue.metres)
    }

    @Test fun simulationIsLabeledAndItsPositionMismatchStillBlocksDirections() {
        val data = signals("SIMULATION_GUIDING")
        assertEquals("기본 내비 모의주행", driveCue(session, data, now).source)
        val mismatch = data + ("navigation_location" to signal("37.42,127.10,0"))
        assertEquals("정차 후 경로를 확인하세요", driveCue(session, mismatch, now).title)
    }

    @Test fun oldOrOtherCourseInstructionsAndUnalignedLocationsDoNotShowDistances() {
        val data = signals()
        val stale = data + ("native_course_tbt" to data.getValue("native_course_tbt").copy(receivedMs = now - 3501))
        assertNull(driveCue(session, stale, now).metres)
        val other = data + ("native_course_tbt" to signal("other-route|TURN_LEFT|80|다른 코스"))
        assertNull(driveCue(session, other, now).metres)
        assertNull(driveCue(session, data - "navigation_location", now).metres)
        val oldFix = data + ("location" to data.getValue("location").copy(sampleNs = (now - 3001) * 1_000_000))
        assertEquals("위치 신호 대기 중", driveCue(session, oldFix, now).title)
    }
}
