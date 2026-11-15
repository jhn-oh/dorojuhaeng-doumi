package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LocalLoopTest {
    @Test fun anUnroutableCandidateIsRetriedCloserToTheOrigin() {
        val origin = GeoPoint(37.4127212, 127.094681)
        val radii = mutableListOf<Double>()
        val route = LocalLoopPlanner.timedLoop(origin, 20) { via ->
            radii += distanceMeters(origin, via.first())
            if (radii.size == 1) throw IOException("No suitable road near waypoint")
            PracticeRoute("loop", "test", "start", "return", listOf(origin) + via + origin, emptyList(),
                routingSeconds = 20 * 60.0, waypoints = via, distance = 2000, origin = origin)
        }
        assertEquals(20, route.minutes)
        assertTrue(radii.last() < radii.first())
    }

    @Test fun nineteenMinuteCandidateIsResizedBeforeReturningATenMinuteLoop() {
        val origin = GeoPoint(37.4127212, 127.094681)
        val radii = mutableListOf<Double>()
        val route = LocalLoopPlanner.timedLoop(origin, 10) { via ->
            radii += distanceMeters(origin, via.first())
            PracticeRoute("loop", "test", "start", "return", listOf(origin) + via + origin, emptyList(),
                routingSeconds = if (radii.size == 1) 19 * 60.0 else 10 * 60.0,
                waypoints = via, distance = 2000, origin = origin)
        }
        assertEquals(10, route.minutes)
        assertEquals(2, radii.size)
        assertTrue(radii.last() < radii.first() * .6)
    }

    @Test fun anOutOfToleranceLoopIsNeverReturnedAsTheRequestedDuration() {
        val origin = GeoPoint(37.4127212, 127.094681)
        var attempts = 0
        try {
            LocalLoopPlanner.timedLoop(origin, 10) { via ->
                attempts++
                PracticeRoute("loop", "test", "start", "return", listOf(origin) + via + origin, emptyList(),
                    routingSeconds = 19 * 60.0, waypoints = via, distance = 2000, origin = origin)
            }
            fail("A 19-minute loop must not satisfy a 10-minute request")
        } catch (expected: IOException) {
            assertTrue(expected.message.orEmpty().contains("10분"))
            assertEquals(6, attempts)
        }
    }

    @Test fun candidatesFollowTheReceivedOriginRatherThanSeoulCatalog() {
        val origins = listOf(GeoPoint(35.1796, 129.0756), GeoPoint(37.5665, 126.9780))
        origins.forEach { origin ->
            val via = loopCandidates(origin, 10, 0.0)
            assertEquals(2, via.size)
            via.forEach { assertEquals(550.0, distanceMeters(origin, it), 1.0) }
            assertTrue(distanceMeters(via.first(), via.last()) > 900)
        }
    }

    @Test fun routeMustReturnToCapturedOriginAndContainRoadGeometry() {
        val origin = GeoPoint(35.1796, 129.0756)
        val via = loopCandidates(origin, 10, 0.0)
        val loop = PracticeRoute("local-loop-test", "주변 한 바퀴", "현재 위치", "현재 위치",
            listOf(origin) + via + origin, emptyList(), routingSeconds = 600.0,
            waypoints = via, distance = 2000, origin = origin)
        assertTrue(validLocalLoop(loop))
        assertEquals(origin, loop.finish)
        assertFalse(validLocalLoop(loop.copy(points = loop.points.dropLast(1))))
        assertFalse(validLocalLoop(loop.copy(origin = CourseCatalog.full.points.first())))
        assertFalse(validLocalLoop(loop.copy(routingSeconds = null)))
        assertFalse(validLocalLoop(loop.copy(waypoints = emptyList())))
        assertSame(loop, PracticeSession(startedAtMs = 0, startedWallMs = 0,
            expectedDoors = emptyList(), plannedRoute = loop).practiceRoute)
    }

    @Test fun staleOrLastKnownLocationCannotBecomeALoopOrigin() {
        val now = 20_000L
        val fix = Signal("35.1796,129.0756,0,5,1000", "VALID", "FusedLocation/1.1.3", now, "CALLBACK", sampleNs = now * 1_000_000)
        assertEquals(GeoPoint(35.1796, 129.0756), locationPoint(fix, now))
        assertNull(locationPoint(fix, now + LOCATION_MAX_AGE_MS + 1))
        assertNull(locationPoint(fix.copy(origin = "LAST_KNOWN"), now))
        assertNull(locationPoint(fix.copy(value = "35.1796,129.0756,0,80,1000"), now))
    }
}
