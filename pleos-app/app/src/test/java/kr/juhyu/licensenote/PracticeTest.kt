package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test

class PracticeTest {
    private val doorKeys = listOf("ROW_1_LEFT", "ROW_1_RIGHT", "ROW_2_LEFT", "ROW_2_RIGHT").map { "door:$it" }
    @Test fun automaticBuckleConfirmationRequiresTheCurrentDriverBuckleValue() {
        val buckled = Signal("true", "VALID", receivedMs = 4000)
        assertTrue(buckled.driverBeltFastened(5000))
        assertFalse(buckled.copy(value = "false").driverBeltFastened(5000))
        assertFalse(buckled.copy(quality = "ERROR").driverBeltFastened(5000))
        assertFalse(buckled.driverBeltFastened(6501))
        assertFalse((null as Signal?).driverBeltFastened(5000))
    }
    @Test fun rechecksAllReportedDoorsWhenStartingEvenAfterManualConfirmation() {
        val now = 5000L
        val closed = doorKeys.associateWith { Signal("0", "VALID", receivedMs = now) }
        assertTrue(departureDoors(closed, now).canConfirm(false))
        val reopened = departureDoors(closed + ("door:REAR" to Signal("1", "VALID", receivedMs = now)), now)
        assertEquals("door:REAR", reopened.firstOpen)
        assertFalse(reopened.canConfirm(false))
        assertFalse(reopened.canConfirm(true))
    }
    @Test fun missingStaleAndInvalidDoorPositionsRequireManualConfirmation() {
        val now = 5000L
        val closed = doorKeys.associateWith { Signal("0", "VALID", receivedMs = now) }
        val driverDoor = doorKeys.first()
        val snapshots = listOf(closed - driverDoor, closed + (driverDoor to Signal("-1", "VALID", receivedMs = now)),
            closed + (driverDoor to Signal(null, "VALID", receivedMs = now)),
            closed + (driverDoor to Signal("0", "VALID", receivedMs = now - 2501)),
            closed + (driverDoor to Signal("0", "ERROR", receivedMs = now)))
        for (signals in snapshots) {
            val doors = departureDoors(signals, now)
            assertNull(doors.firstOpen)
            assertFalse(doors.canConfirm(false))
            assertTrue(doors.canConfirm(true))
        }
    }
    private fun fixture(): PracticeSession {
        val samples = (1000L..11000L step 1000L).flatMap { now ->
            (doorKeys.map { it to "0" } + listOf("turn" to "OFF", "gear" to if (now == 11000L) "4" else "8")).map { (key, value) ->
                RecordedSignal(now, key, value, "VALID", "SDK", "CALLBACK")
            }
        }
        return PracticeSession(startedAtMs = 1000, startedWallMs = 1_790_000_000_000, endedAtMs = 11000, phase = SessionPhase.COMPLETE, expectedDoors = doorKeys, samples = samples, passage = Passage("one-opportunity", 4000, 7000), observation = Observation.LEFT)
    }
    private fun snapshot(now: Long, gear: String = "4", speed: String = "0.0", heading: Double = 0.0, point: GeoPoint = LegacyLaneCourse.start): Map<String, Signal> =
        (doorKeys.associateWith { Signal("0", "VALID", "Vehicle/Door", now, "CALLBACK") } + mapOf(
            "speed" to Signal(speed, "VALID", "AAOS", now), "gear" to Signal(gear, "VALID", "AAOS", now),
            "ux" to Signal("false,0", "VALID", "AAOS", 0), "turn" to Signal("OFF", "VALID", "Vehicle/TurnSignal", now),
            "location" to Signal("${point.lat},${point.lon},$heading,5.0,${now + 100000}", "VALID", "FusedLocation", now),
        ))
    @Test fun corroboratedSignalOmissionScoresOnlyThreeCriteria() {
        val a = PracticeEvaluator.evaluate(fixture())
        assertEquals(93, a.score); assertEquals(3, a.checked); assertTrue(a.mistake)
    }
    @Test fun theSixObservationAndDoorBranchesAreDistinct() {
        for (manual in listOf(false, true)) for (answer in listOf(Observation.LEFT, Observation.NOT_LEFT, Observation.UNSEEN)) {
            val a = PracticeEvaluator.evaluate(fixture().copy(manualDoor = manual, observation = answer))
            when {
                answer == Observation.NOT_LEFT -> { assertEquals("NO_DEDUCTION", a.status); assertFalse(a.mistake); assertNull(a.score) }
                answer == Observation.UNSEEN || manual -> { assertEquals("HELD", a.status); assertFalse(a.mistake); assertNull(a.score) }
                else -> assertEquals(93, a.score)
            }
        }
    }
    @Test fun noObservationCannotBecomeAnError() { assertNull(PracticeEvaluator.evaluate(fixture().copy(observation = Observation.UNANSWERED)).score) }
    @Test fun aKnownUnbuckledBeltWhileMovingHoldsTheScoreButPreservesTheConfirmedError() {
        val s = fixture().let { s -> s.copy(samples = s.samples + listOf(RecordedSignal(5000, "belt", "false", "VALID", "Vehicle/Seat", "CALLBACK"), RecordedSignal(5000, "speed", "5.0", "VALID", "AAOS", "CALLBACK"))) }
        val a = PracticeEvaluator.evaluate(s)
        assertEquals("HELD", a.status); assertNull(a.score); assertTrue(a.mistake)
    }
    @Test fun aSingleOffSampleIsNotContinuousEvidence() {
        val s = fixture().let { s -> s.copy(samples = s.samples.filter { it.key != "turn" || it.atMs == 4000L }) }
        assertEquals("HELD", PracticeEvaluator.evaluate(s).status)
    }
    @Test fun missingDoorAreaPreventsFullScore() {
        val s = fixture().copy(expectedDoors = doorKeys.dropLast(1))
        assertEquals("HELD", PracticeEvaluator.evaluate(s).status)
    }
    @Test fun invalidDoorValueIsNotClosed() {
        val s = fixture().let { s -> s.copy(samples = s.samples.map { if (it.key == doorKeys[0] && it.atMs == 5000L) it.copy(value = null, quality = "UNAVAILABLE") else it }) }
        assertNull(PracticeEvaluator.evaluate(s).score)
    }
    @Test fun openingDuringTheSessionPreventsDoorCoverage() {
        val s = fixture().let { s -> s.copy(samples = s.samples.map { if (it.key == doorKeys[0] && it.atMs == 5000L) it.copy(value = "1") else it }) }
        assertEquals("HELD", PracticeEvaluator.evaluate(s).status)
    }
    @Test fun useOfLeftSignalDoesNotBecomeAnOmission() {
        val s = fixture().let { s -> s.copy(samples = s.samples.map { if (it.key == "turn" && it.atMs == 5000L) it.copy(value = "LEFT") else it }) }
        val a = PracticeEvaluator.evaluate(s)
        assertEquals("OBSERVED", a.status); assertNull(a.score); assertFalse(a.mistake)
    }
    @Test fun aBriefSignalBetweenPeriodicSamplesIsPreservedAndCannotBecomeAnOmission() {
        val e = PracticeEngine(PracticeSession(startedAtMs = 1000, startedWallMs = 0, expectedDoors = doorKeys))
        e.tick(snapshot(1000), 1000)
        val off = Signal("OFF", "VALID", "Vehicle/TurnSignal", 1000, "CALLBACK")
        val on = off.copy(value = "LEFT", receivedMs = 4500)
        val backOff = off.copy(receivedMs = 4600)
        e.signalChanged("turn", on, off)
        e.signalChanged("turn", backOff, on)
        val pulse = e.session.samples.filter { it.atMs in 4500L..4600L }
        assertEquals(listOf("LEFT", "OFF"), pulse.map { it.value })
        val s = fixture().copy(samples = fixture().samples + pulse)
        assertEquals("OBSERVED", PracticeEvaluator.evaluate(s).status)
        assertFalse(PracticeEvaluator.evaluate(s).mistake)
    }
    @Test fun rightSignalCannotBeTreatedAsAllOff() {
        val s = fixture().let { s -> s.copy(samples = s.samples.map { if (it.key == "turn" && it.atMs == 5000L) it.copy(value = "RIGHT") else it }) }
        assertEquals("HELD", PracticeEvaluator.evaluate(s).status)
    }
    @Test fun missingTurnSignalDoesNotBecomeOff() {
        val s = fixture().let { s -> s.copy(samples = s.samples.map { if (it.key == "turn" && it.atMs == 5000L) it.copy(value = null, quality = "ERROR") else it }) }
        assertNull(PracticeEvaluator.evaluate(s).score)
    }
    @Test fun missingTimeRangeCannotBeFilledWithLastValue() {
        val s = fixture().let { s -> s.copy(samples = s.samples.filterNot { it.atMs in 4000L..6000L }) }
        assertNull(PracticeEvaluator.evaluate(s).score)
    }
    @Test fun sessionInterruptionAndSafetyIssueBothHoldScores() {
        assertNull(PracticeEvaluator.evaluate(fixture().copy(interrupted = true)).score)
        assertNull(PracticeEvaluator.evaluate(fixture().copy(safetyIssue = true)).score)
        assertNull(PracticeEvaluator.evaluate(fixture().copy(dataGap = true)).score)
    }
    @Test fun aMissedOpportunityIsNotAMistake() {
        val a = PracticeEvaluator.evaluate(fixture().copy(passage = null))
        assertEquals("NO_OPPORTUNITY", a.status); assertFalse(a.mistake)
    }
    @Test fun anIncompletePassageDoesNotCountAsPractice() { assertEquals("NO_OPPORTUNITY", PracticeEvaluator.evaluate(fixture().copy(passage = Passage("enter-only", 4000))).status) }
    @Test fun insufficientPostPassageEvidenceHoldsTheScore() { assertNull(PracticeEvaluator.evaluate(fixture().copy(endedAtMs = 8000)).score) }
    @Test fun observerCorrectionRemovesTheErrorAndKeepsTheSameEvent() {
        val e = PracticeEngine(fixture().copy(nextPractice = true))
        e.observe(Observation.NOT_LEFT)
        assertFalse(PracticeEvaluator.evaluate(e.session).mistake)
        assertEquals("one-opportunity", e.session.passage?.id)
        assertEquals(2, e.session.revision)
        assertFalse(e.session.nextPractice)
        val safety = PracticeEngine(fixture().copy(safetyIssue = true, nextPractice = true))
        safety.observe(Observation.LEFT)
        assertTrue(safety.session.safetyIssue)
        assertNull(PracticeEvaluator.evaluate(safety.session).score)
        assertFalse(safety.session.nextPractice)
    }
    @Test fun stoppedInDriveDoesNotUnlockReview() { assertFalse(canReview(snapshot(1000, gear = "8"), 1000)) }
    @Test fun unknownUxOrSpeedFailsClosed() {
        assertFalse(canReview(snapshot(1000) - "ux", 1000))
        assertFalse(canReview(snapshot(1000) + ("speed" to Signal()), 1000))
    }
    @Test fun aStableOnChangeUxValueDoesNotExpireByAgeAlone() { assertTrue(canReview(snapshot(100000), 100000)) }
    @Test fun staleSpeedDoesNotUnlockReview() { assertFalse(canReview(snapshot(1000), 5000)) }
    @Test fun parkingSignalLossLocksTheLessonWithoutEndingItUntilDrivingIsConfirmed() {
        val parked = snapshot(1000)
        for (unknown in listOf(parked - "gear", parked - "ux", parked - "speed",
            parked + ("gear" to Signal("0", "VALID", receivedMs = 1000)),
            parked + ("speed" to Signal("NaN", "VALID", receivedMs = 1000)),
            parked + ("ux" to Signal("true,1", "ERROR")))) {
            assertFalse(canReview(unknown, 1000))
            assertFalse(guideDrivingConfirmed(unknown, 1000))
        }
        assertFalse(canReview(parked, 5000))
        assertFalse(guideDrivingConfirmed(parked, 5000))
        assertTrue(canReview(snapshot(5000), 5000))
        for (driving in listOf(snapshot(5000, gear = "8"), snapshot(5000, gear = "2"),
            snapshot(5000, speed = "-0.05") - "gear", parked + ("ux" to Signal("true,0", "VALID")),
            parked + ("ux" to Signal("false,1", "VALID")))) {
            assertTrue(guideDrivingConfirmed(driving, 5000))
            assertFalse(canReview(driving, 5000))
        }
    }
    @Test fun malformedAndOutOfRangeLocationAreRejected() {
        assertNull(locationPoint(Signal("NaN,127.03,0,5,1", "VALID", receivedMs = 1000), 1000))
        assertNull(locationPoint(Signal("91,127.03,0,5,1", "VALID", receivedMs = 1000), 1000))
        assertNull(locationPoint(Signal("37.48,127.03,0,5,0", "VALID", receivedMs = 1000), 1000))
    }
    @Test fun wrongDirectionDoesNotCreateAPassage() {
        val e = PracticeEngine(PracticeSession(startedAtMs = 1000, startedWallMs = 0, expectedDoors = doorKeys))
        e.tick(snapshot(1000, "8", "5.0", 180.0, LegacyLaneCourse.entry), 1000)
        assertNull(e.session.passage)
    }
    @Test fun orderedLocationEventsCreateOnePassageAndDeduplicateTicks() {
        val e = PracticeEngine(PracticeSession(startedAtMs = 1000, startedWallMs = 0, expectedDoors = doorKeys))
        e.tick(snapshot(1000, "8", "5.0", point = LegacyLaneCourse.entry), 1000)
        e.tick(snapshot(2000, "8", "5.0", point = GeoPoint(37.4828, 127.033)), 2000)
        e.tick(snapshot(3000, "8", "5.0", point = LegacyLaneCourse.exit), 3000)
        assertEquals(3000L, e.session.passage?.exitMs)
        val size = e.session.samples.size
        e.tick(snapshot(3000, "8", "5.0", point = LegacyLaneCourse.exit), 3000)
        assertEquals(size, e.session.samples.size)
        assertEquals(1, e.session.samples.count { it.key == "turn" && it.atMs == 3000L })
    }
    @Test fun aPositionTeleportIsMarkedAsAGap() {
        val e = PracticeEngine(PracticeSession(startedAtMs = 1000, startedWallMs = 0, expectedDoors = doorKeys))
        e.tick(snapshot(1000, "8", "5.0"), 1000)
        e.tick(snapshot(2000, "8", "5.0", point = GeoPoint(38.0, 127.033)), 2000)
        assertTrue(e.session.dataGap); assertNull(e.session.passage)
    }
    @Test fun finishRequiresActualParkAndCreatesAnEndSnapshot() {
        val e = PracticeEngine(PracticeSession(startedAtMs = 1000, startedWallMs = 0, expectedDoors = doorKeys))
        e.tick(snapshot(1000), 1000)
        assertFalse(e.finish(snapshot(2000, "8"), 2000))
        assertTrue(e.finish(snapshot(2000), 2000))
        assertEquals(SessionPhase.REVIEW, e.session.phase)
        assertEquals("4", e.session.samples.last { it.key == "gear" }.value)
        assertFalse(e.finish(snapshot(2000), 2000))
    }
}

