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
        assertEquals(5, CourseCatalog.all.map { it.id }.toSet().size)
        assertEquals(5, CourseCatalog.all.map { it.finish }.toSet().size)
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
}
