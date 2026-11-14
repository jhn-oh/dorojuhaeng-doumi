package kr.juhyu.licensenote

import ai.pleos.playground.location.fused.model.LocationData
import org.junit.Assert.*
import org.junit.Test

class DriveHelpTest {
    @Test fun retrievesReviewedTopicsAndDeclinesUnknownOrLiveDecisions() {
        assertEquals(GuideTopic.WIPER, GuideAnswers.find("와이퍼 한 번")?.topic)
        assertEquals(GuideTopic.DEFOG, GuideAnswers.find("유리에 김이 서려")?.topic)
        assertEquals(GuideTopic.BELT, GuideAnswers.find("안전띠")?.topic)
        assertNull(GuideAnswers.find("오늘 점심 메뉴는"))
        assertNull(GuideAnswers.find("지금 차선 변경해도 돼?"))
        assertNull(GuideAnswers.find("와이퍼가 고장났어"))
        assertNull(GuideAnswers.find("브레이크가 안 들어"))
        assertNull(GuideAnswers.find("와이퍼 수리 비용"))
        assertEquals(GuideTopic.SIGNAL, GuideAnswers.find("깜빡이는 어떻게 켜는 거야")?.topic)
        assertNull(GuideAnswers.find(""))
    }
    @Test fun explicitListeningWindowRequiresWakeWordAtStart() {
        assertEquals("와이퍼 어떻게 켜", wakeQuestion("도움 에이 아이, 와이퍼 어떻게 켜"))
        assertEquals("", wakeQuestion("도움AI"))
        assertNull(wakeQuestion("옆에서 도움AI라고 했어"))
    }
    @Test fun bothAxesMustComeFromOneFreshFiniteSensorSample() {
        val m = motionSample(9.80665f, -9.80665f, 100L, 200L)!!
        assertEquals(1f, m.right!!, 0.00001f)
        assertEquals(-1f, m.forward, 0.00001f)
        assertEquals(1.41421f, m.magnitude, 0.0001f)
        assertNull(motionSample(Float.NaN, 0f, 100L, 100L))
        assertNull(motionSample(0f, 0f, 100L, 2_000_000_101L))
        assertNull(motionSample(0f, 0f, 200L, 100L))
    }
    @Test fun freshVehicleSamplesProvideLongitudinalOnlyAndRequireNewPairsAfterGaps() {
        val meter = SpeedG()
        fun speed(t: Long, v: String) = Signal(value = v, quality = "VALID", sampleNs = t)
        val first = 1_000_000_000L
        assertNull(meter.sample(speed(first, "0"), first))
        val second = first + 1_000_000_000L
        val reading = meter.sample(speed(second, "9.80665"), second)!!
        assertNull(reading.right)
        assertEquals(1f, reading.forward, 0.0001f)
        assertNull(meter.sample(speed(second, "9.80665"), second + 1_500_000_001L))
        assertNull(meter.sample(speed(second + 2_000_000_000L, "0"), second + 2_000_000_000L))
    }
    @Test fun wiperNeedsFreshPostOpeningEdgeFromActualVehicleDomain() {
        val on = Signal(value = "2020", quality = "VALID", source = "Vehicle/Wiper", receivedMs = 2100)
        assertTrue(observedWiperActivation(on, 2000, 2200))
        assertFalse(observedWiperActivation(on, 2050, 2200))
        assertFalse(observedWiperActivation(on, 2000, 4500))
        assertFalse(observedWiperActivation(on.copy(quality = "ERROR"), 2000, 2200))
    }
    @Test fun courseCueFollowsPolylineAndHoldsOffCourseOrOppositeHeading() {
        val route = CourseCatalog.full
        val first = nextPracticeCue(route, route.points.first(), 4.0)
        assertEquals("LEFT", first.turnKey)
        assertTrue(first.metres!! in 45..65)
        val second = nextPracticeCue(route, route.points[2], 4.0)
        assertEquals("RIGHT", second.turnKey)
        assertTrue(second.metres!! in 55..75)
        assertNull(nextPracticeCue(route, GeoPoint(37.4127, 127.0946), 4.0).turnKey)
        assertNull(nextPracticeCue(route, route.points.first(), 30.0).turnKey)
        val reverse = (bearingDegrees(route.points[0], route.points[1]) + 180) % 360
        assertNull(nextPracticeCue(route, route.points.first(), 4.0, reverse).turnKey)
    }
    @Test fun aRepeatedFusedFixExpiresFromMeasurementAndLiveGuidanceRecoversWithANewFix() {
        val route = CourseCatalog.full
        val start = route.points.first()
        val wall = 1_794_654_000_000L
        val elapsed = 10_000L
        val measured = LocationData(start.lat, start.lon, 0.0, 0f, 5f, wall - 2_000)
        val initial = fusedLocationSignal(measured, wall, elapsed)
        val session = PracticeSession(courseId = route.id, rulesVersion = 5, expectedDoors = emptyList(),
            startedAtMs = elapsed, startedWallMs = wall)
        val repeated = fusedLocationSignal(measured, wall + 1000, elapsed + 1000)
        assertEquals(initial.sampleNs, repeated.sampleNs)
        assertEquals("LEFT", driveCue(session, mapOf("location" to repeated), elapsed + 1000).turnKey)
        assertNull(locationPoint(repeated, elapsed + 1001))
        val stale = fusedLocationSignal(measured, wall + 1001, elapsed + 1001)
        assertEquals("UNAVAILABLE", stale.quality)
        assertEquals("위치 신호 대기 중", driveCue(session, mapOf("location" to stale), elapsed + 1001).title)
        val engine = PracticeEngine(session)
        engine.tick(mapOf("location" to stale), elapsed + 1001)
        assertEquals("UNAVAILABLE", engine.session.samples.single { it.key == "location" }.quality)
        val next = fusedLocationSignal(measured.copy(timestampMillis = wall + 2000), wall + 2000, elapsed + 2000)
        engine.tick(mapOf("location" to next), elapsed + 2000)
        assertEquals("VALID", engine.session.samples.last { it.key == "location" }.quality)
        assertEquals("LEFT", driveCue(session, mapOf("location" to next), elapsed + 2000).turnKey)
        for (stamp in listOf(0L, wall + 60_000)) {
            assertNull(locationPoint(fusedLocationSignal(measured.copy(timestampMillis = stamp), wall, elapsed), elapsed))
        }
    }
    @Test fun nativeNavigationRequiresFreshMatchingCourseLocationTurnAndDistance() {
        val route = CourseCatalog.full
        val p = route.points.first()
        val now = 10_000L
        fun signal(value: String) = Signal(value = value, quality = "VALID", receivedMs = now, origin = "CALLBACK")
        val signals = mapOf("location" to signal("${p.lat},${p.lon},0,4,999"),
            "navigation_location" to signal("${p.lat},${p.lon}"),
            "navigation_route" to signal("true,ROUTE"), "navigation_status" to signal("ROUTE_DRIVE"),
            "course_tbt" to signal("${route.id}|TURN_LEFT|56"))
        val session = PracticeSession(startedAtMs = 5000, startedWallMs = 5000, expectedDoors = emptyList(), rulesVersion = 2)
        assertEquals("기본 내비 안내", driveCue(session, signals, now).source)
        for (bad in listOf("course_tbt" to signal("wrong|TURN_LEFT|56"), "course_tbt" to signal("${route.id}|TURN_RIGHT|56"),
            "course_tbt" to signal("${route.id}|TURN_LEFT|300"),
            "course_tbt" to signal("${route.id}|TURN_LEFT|56").copy(receivedMs=6000))) {
            assertTrue(driveCue(session, signals + bad, now).source.startsWith("선택 코스"))
        }
        val conflicting = driveCue(session, signals + ("navigation_location" to signal("37.4127,127.0946")), now)
        assertNull(conflicting.turnKey)
        assertEquals("정차 후 경로를 확인하세요", conflicting.title)
        val nativeGuidingWithoutStatus = (signals - "navigation_status") + mapOf(
            "navigation_route" to signal("false,ROUTE_GUIDING"),
            "navigation_location" to signal("37.4127212,127.094681"))
        assertEquals(conflicting, driveCue(session, nativeGuidingWithoutStatus, now))
    }
    @Test fun vehicleCounterSummaryRequiresConsistentStartAndEndSamples() {
        fun sample(t: Long, value: String) = RecordedSignal(t, "odometer_km", value, "VALID", "Vehicle/Odometer", "READ")
        val finished = PracticeSession(startedAtMs = 1000, startedWallMs = 1000, expectedDoors = emptyList(),
            endedAtMs = 6000, samples = listOf(sample(1000, "50"), sample(3000, "50.1"), sample(6000, "50.4")))
        assertEquals(0.4, vehicleCounterDelta(finished, "odometer_km")!!, 0.0001)
        assertNull(vehicleCounterDelta(finished.copy(samples = listOf(sample(1000, "50"), sample(6000, "50.4"))), "odometer_km"))
        assertNull(vehicleCounterDelta(finished.copy(samples = listOf(sample(1000, "50"), sample(3000, "49"), sample(6000, "50.4"))), "odometer_km"))
    }
}
