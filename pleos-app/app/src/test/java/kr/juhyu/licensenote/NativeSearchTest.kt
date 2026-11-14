package kr.juhyu.licensenote

import ai.pleos.playground.navi.data.NavigationScreenRequest
import org.junit.Assert.*
import org.junit.Test

class NativeSearchTest {
    private fun signal(value: String, time: Long) = Signal(value = value, quality = "VALID", receivedMs = time)
    private val events = mapOf(
        "navigation_screen" to signal("SDK_SEARCH_REQUESTED", 200),
        "navigation_destination_changed" to signal("CHANGED", 300),
        "navigation_status" to signal("ROUTE_DRIVE", 310),
        "navigation_destination" to signal("new destination payload", 320),
    )

    @Test fun publicSdkCarriesTheUserKeywordAndRequiresAConfirmedProviderSelection() {
        val req = NavigationScreenRequest.SearchPlace("서울역")
        assertEquals("서울역", req.keyword)
        assertEquals(events["navigation_destination"], nativeDestinationEvent(events, 100, 350))
        assertNull(nativeDestinationEvent(events - "navigation_destination_changed", 100, 350))
        assertNull(nativeDestinationEvent(events + ("navigation_destination" to signal("stale", 250)), 100, 350))
        assertNull(nativeDestinationEvent(events + ("navigation_request" to signal("CANCELLED", 321)), 100, 350))
        assertNull(nativeDestinationEvent(events + ("navigation_status" to signal("FREE_DRIVE", 321)), 100, 350))
        assertNull(nativeDestinationEvent(events, 100, 25_000))
        assertNull(nativeDestinationEvent(events, 400, 420))
        val routeStarted = (events - "navigation_destination_changed") + ("navigation_search" to signal("판교역 신분당선,3100,720", 305)) +
            ("navigation_request" to signal("CANCELLED", 299))
        assertEquals(events["navigation_destination"], nativeDestinationEvent(routeStarted, 100, 350, "판교역 신분당선", 3100))
        assertNull(nativeDestinationEvent(routeStarted, 100, 350, "서운로 북쪽 도착점", 3100))
        assertNull(nativeDestinationEvent(routeStarted, 100, 350, "판교역 신분당선", 13000))
        assertNull(nativeDestinationEvent(routeStarted + ("navigation_request" to signal("CANCELLED", 322)), 100, 350, "판교역 신분당선", 3100))
        val withoutStatus = events - "navigation_status"
        assertEquals(events["navigation_destination"], nativeDestinationEvent(withoutStatus +
            ("navigation_route" to signal("false,ROUTE_GUIDING", 315)), 100, 350))
        assertNull(nativeDestinationEvent(withoutStatus + ("navigation_route" to signal("false,ROUTE_GUIDING", 280)), 100, 350))
        assertNull(nativeDestinationEvent(withoutStatus + ("navigation_route" to signal("false,FREE_DRIVE", 315)), 100, 350))
        assertNull(nativeDestinationEvent(withoutStatus + ("navigation_route" to signal("false,ROUTE_GUIDING", 315)) +
            ("navigation_status" to signal("FREE_DRIVE", 322)), 100, 350))
    }

    @Test fun externalDestinationStaysInTheSessionWithoutInventingAStreetPolyline() {
        val selected = NativeDestination("서울역", GeoPoint(37.5546, 126.9706), 10500, 1500)
        val route = selected.route()
        val session = PracticeSession(courseId = route.id, rulesVersion = 4, startedAtMs = 1,
            startedWallMs = 1, expectedDoors = emptyList(), nativeDestination = selected)
        assertTrue(selected.samePlace(selected.copy(meters = 10450)))
        assertFalse(selected.samePlace(selected.copy(point = GeoPoint(37.5, 126.9706))))
        assertEquals(listOf(selected.point), session.practiceRoute.points)
        assertTrue(session.practiceRoute.waypoints.isEmpty())
        assertNull(PracticeEvaluator.evaluate(session).score)
        val engine = PracticeEngine(session)
        val parked = mapOf("speed" to signal("0", 5000), "gear" to signal("4", 5000), "ux" to signal("false,0", 5000))
        assertTrue(engine.finish(parked, 5000))
        assertEquals(SessionPhase.COMPLETE, engine.session.phase)
    }
}
