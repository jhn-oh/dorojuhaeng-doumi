package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test

class CourseSelectionTest {
    @Test fun matchedRequestsPreserveConditionsAndDestination() {
        assertEquals(listOf(CourseCatalog.right), matchCourseRequest("우회전만 연습하고 싶어").routes)
        assertTrue(matchCourseRequest("고속도로 제외하고 30분 넓은 도로").routes.isEmpty())
        assertTrue(matchCourseRequest("혼잡한 도로에서 우회전").routes.isEmpty())
        assertTrue(matchCourseRequest("").routes.isEmpty())
        assertTrue(matchCourseRequest("우회전 -4분").routes.isEmpty())
        assertTrue(matchCourseRequest("우회전 99999999999999999분").routes.isEmpty())
        assertEquals(listOf(CourseCatalog.lane), matchCourseRequest("차선 변경 연습").routes)
        assertEquals(listOf(CourseCatalog.fortytwo), matchCourseRequest("포티투닷까지 3분").routes)
        assertTrue(matchCourseRequest("42dot까지 우회전만").routes.isEmpty())
        assertTrue(matchCourseRequest("${CourseCatalog.right.minutes}분 우회전").routes.contains(CourseCatalog.right))
    }

    @Test fun previewRouteIsTheRecordedCourseAndTheNavigationRoute() {
        assertEquals(11, CourseCatalog.all.map { it.id }.toSet().size)
        assertEquals(8, CourseCatalog.all.map { it.finish }.toSet().size)
        CourseCatalog.all.forEach { route ->
            val session = PracticeSession(courseId = route.id, rulesVersion = route.rulesVersion,
                startedAtMs = 0, startedWallMs = 0, expectedDoors = emptyList())
            assertSame(route, session.practiceRoute)
            assertTrue(route.points.containsAll(route.waypoints))
            assertTrue(route.distance > 0 && route.minutes > 0)
        }
        assertEquals(listOf(PracticeCourse.turns.last().corner), CourseCatalog.right.waypoints)
        assertEquals(listOf(LegacyLaneCourse.entry), CourseCatalog.lane.waypoints)
        val oldLane = PracticeSession(startedAtMs = 0, startedWallMs = 0, expectedDoors = emptyList())
        assertSame(CourseCatalog.lane, oldLane.practiceRoute)
    }

    @Test fun broadRoadRequestBuildsTheConnectedHalfHourCourse() {
        val request = "넓은 도로에서 운전해보고 싶어. 다만 고속도로는 아직 무서워. 총 30분 정도 되도록 코스 짜줘"
        val route = matchCourseRequest(request).routes.single()
        assertTrue(route.verifiedArterial)
        assertEquals(28, route.minutes)
        assertEquals(8709, route.distance)
        assertEquals(GeoPoint(37.498100, 127.028307), route.points.first())
        assertEquals(GeoPoint(37.524396, 127.117032), route.finish)
        assertEquals(listOf(GeoPoint(37.500752, 127.037123), GeoPoint(37.510472, 127.070466)), route.waypoints)
        val subroute = matchCourseRequest("올림픽공원까지 넓은 도로 15분").routes.single()
        assertEquals(route.finish, subroute.finish)
        assertTrue(subroute.waypoints.isEmpty())
        assertTrue(matchCourseRequest("넓은 도로 30분").routes.isEmpty())
        assertTrue(matchCourseRequest("넓은 도로 30분 정도 고속도로 제외하고 유턴도").routes.isEmpty())
        assertTrue(matchCourseRequest("양재에서 넓은 도로로 30분 정도").routes.isEmpty())
    }
}
