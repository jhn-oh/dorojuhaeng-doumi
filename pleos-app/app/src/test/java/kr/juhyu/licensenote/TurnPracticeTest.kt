package kr.juhyu.licensenote

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class TurnPracticeTest {
    @Test fun onlyTurnsOverlappingARecordedGapAreHeldInNewSessions() {
        val base = evaluatedFixture()
        val subscriptions = base.samples.map { it.copy(key = "turn_subscription", value = "READY") }
        val session = base.copy(rulesVersion = 3, samples = base.samples + subscriptions, dataGap = true, evidenceGaps = listOf(EvidenceGap(4200, 8300)))
        assertEquals(listOf("HELD", "OBSERVED"), PracticeEvaluator.turnAssessments(session).map { it.status })
        assertEquals(listOf("OMISSION", "OBSERVED"), PracticeEvaluator.turnAssessments(session.copy(evidenceGaps = listOf(EvidenceGap(23000, 24000)))).map { it.status })
        assertTrue(PracticeEvaluator.turnAssessments(session.copy(rulesVersion = 2)).all { it.status == "HELD" })
        assertTrue(PracticeEvaluator.turnAssessments(session.copy(dataGap = false, samples = base.samples)).all { it.status == "HELD" })
    }
    private fun signal(value: String, now: Long, source: String = "SDK") = Signal(value, "VALID", source, now, "CALLBACK")
    private fun snapshot(now: Long, point: GeoPoint, steering: Double, moving: Boolean = true) = mapOf(
        "speed" to signal(if (moving) "5.0" else "0.0", now), "gear" to signal(if (moving) "8" else "4", now),
        "ux" to signal(if (moving) "true,80" else "false,0", now), "turn" to signal("OFF", now),
        "turn_subscription" to signal("READY", now), "steering_subscription" to signal("READY", now),
        "steering" to signal(steering.toString(), now, "Vehicle/SteeringWheel front-wheel degrees"),
        "location" to signal("${point.lat},${point.lon},0.0,5.0,${100_000 + now}", now, "FusedLocation"),
    )
    private fun replay(steeringFactor: Double = 1.0, moving: Boolean = true, rules: Int = 2): PracticeSession {
        val e = PracticeEngine(PracticeSession(rulesVersion = rules, startedAtMs = 1000, startedWallMs = 0, expectedDoors = emptyList()))
        var now = 1000L
        for ((a, b) in PracticeCourse.points.zipWithNext()) {
            val count = ceil(distanceMeters(a, b) / 5).toInt()
            for (i in 0 until count) {
                val f = i.toDouble() / count
                val p = GeoPoint(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
                val near = PracticeCourse.turns.firstOrNull { distanceMeters(p, it.corner) < 50 }
                val angle = (if (near?.direction == TurnDirection.LEFT) -12.0 else if (near != null) 12.0 else 0.0) * steeringFactor
                e.tick(snapshot(now, p, angle, moving), now)
                now += 1000
            }
        }
        repeat(3) { e.tick(snapshot(now, PracticeCourse.finish, 0.0, moving), now); now += 1000 }
        e.finish(snapshot(now, PracticeCourse.finish, 0.0, false), now)
        return e.session
    }
    private fun evaluatedFixture(): PracticeSession {
        val turns = listOf(
            TurnEvent("session:left", TurnDirection.LEFT, 4000, 8000, 0.0, -90.0, -12.0, Observation.CONFIRMED),
            TurnEvent("session:right", TurnDirection.RIGHT, 16000, 20000, 270.0, 90.0, 12.0, Observation.CONFIRMED),
        )
        val samples = (1000L..24000L step 1000).map { now -> RecordedSignal(now, "turn", if (now in 15000..21000) "RIGHT" else "OFF", "VALID", "Vehicle/TurnSignal", "CALLBACK") }
        return PracticeSession(rulesVersion = 2, startedAtMs = 1000, startedWallMs = 0, endedAtMs = 24000,
            phase = SessionPhase.COMPLETE, expectedDoors = emptyList(), turns = turns, samples = samples)
    }
    @Test fun roadReplayConnectsTwoTurnsWithConcordantSteeringAndGps() {
        val s = replay()
        assertFalse(s.dataGap)
        val complete = s.turns.filter { it.exitMs != null }
        assertEquals(listOf(TurnDirection.LEFT, TurnDirection.RIGHT), complete.map { it.direction })
        assertTrue(complete[0].headingChangeDeg!! < -55)
        assertTrue(complete[1].headingChangeDeg!! > 55)
    }
    @Test fun gpsCourseWithoutSteeringDoesNotCreateATurn() { assertTrue(replay(0.0).turns.none { it.exitMs != null }) }
    @Test fun oppositeSteeringDoesNotConfirmTheExpectedTurn() { assertTrue(replay(-1.0).turns.none { it.exitMs != null }) }
    @Test fun steeringWhileParkedDoesNotCreateATurn() { assertTrue(replay(moving = false).turns.none { it.exitMs != null }) }
    @Test fun aStraightTrackWithSteeringIsNotATurn() {
        val a = GeoPoint(37.0, 127.0)
        val entry = GeoPoint(37.0002, 127.0)
        val exit = GeoPoint(37.0008, 127.0)
        val e = PracticeEngine(PracticeSession(rulesVersion = 2, startedAtMs = 1000, startedWallMs = 0, expectedDoors = emptyList()),
            listOf(CourseTurn("straight", TurnDirection.LEFT, entry, GeoPoint(37.0005, 127.0), exit)))
        repeat(25) { i -> val now = 1000L + i * 1000; e.tick(snapshot(now, GeoPoint(a.lat + i * .00004, a.lon), -12.0), now) }
        assertTrue(e.session.turns.none { it.exitMs != null })
    }
    @Test fun headingWrapsAcrossNorthWithoutInventingAFullTurn() {
        assertEquals(20.0, headingChangeDegrees(350.0, 10.0), .001)
        assertEquals(-20.0, headingChangeDegrees(10.0, 350.0), .001)
    }
    @Test fun poorGpsAccuracyCannotBecomeTurnEvidence() {
        assertNull(locationPoint(signal("37.0,127.0,0,80,100000", 1000), 1000))
    }
    @Test fun leftOmissionAndRightSignalRemainIndependent() {
        val results = PracticeEvaluator.turnAssessments(evaluatedFixture())
        assertEquals(listOf("OMISSION", "OBSERVED"), results.map { it.status })
        assertEquals(listOf(true, false), results.map { it.mistake })
        val result = PracticeEvaluator.evaluate(evaluatedFixture())
        assertNull(result.score); assertEquals(2, result.checked); assertTrue(result.mistake)
    }
    @Test fun twoObserverAnswersAdvanceTheSameEventsThenComplete() {
        val s = evaluatedFixture().copy(phase = SessionPhase.REVIEW, turns = evaluatedFixture().turns.map { it.copy(observation = Observation.UNANSWERED) })
        val e = PracticeEngine(s)
        e.observe(Observation.CONFIRMED)
        assertEquals(SessionPhase.REVIEW, e.session.phase)
        e.observe(Observation.CONFIRMED)
        assertEquals(SessionPhase.COMPLETE, e.session.phase)
        assertEquals(s.turns.map { it.id }, e.session.turns.map { it.id })
    }
    @Test fun correctionTargetsOneTurnAndClearsTheRecommendation() {
        val e = PracticeEngine(evaluatedFixture().copy(nextPractice = true))
        e.observe(Observation.NOT_PERFORMED, eventId = "session:left")
        assertEquals("NO_TURN", PracticeEvaluator.turnAssessments(e.session)[0].status)
        assertEquals(Observation.CONFIRMED, e.session.turns[1].observation)
        assertFalse(PracticeEvaluator.evaluate(e.session).mistake)
        assertFalse(e.session.nextPractice)
    }
    @Test fun unseenOrGappedTurnsCannotBecomeMistakes() {
        val s = evaluatedFixture()
        assertFalse(PracticeEvaluator.evaluate(s.copy(turns = s.turns.map { it.copy(observation = Observation.UNSEEN) })).mistake)
        assertFalse(PracticeEvaluator.evaluate(s.copy(dataGap = true)).mistake)
    }
    @Test fun aBriefCorrectSignalCallbackPreventsFalseOmission() {
        val s = evaluatedFixture()
        val pulse = listOf(RecordedSignal(5100, "turn", "LEFT", "VALID", "SDK", "CALLBACK"), RecordedSignal(5250, "turn", "OFF", "VALID", "SDK", "CALLBACK"))
        assertEquals("OBSERVED", PracticeEvaluator.turnAssessments(s.copy(samples = s.samples + pulse))[0].status)
    }
    @Test fun nextPracticeSelectsOnlyTheConfirmedMistakeAndKeepsItsCourseId() {
        val s = evaluatedFixture().let { base -> base.copy(nextPractice = true, turns = base.turns.map { it.copy(id = "session:${if (it.direction == TurnDirection.LEFT) "left-01" else "right-01"}") }) }
        assertEquals(listOf("left-01"), practiceTargetIds(s))
        assertTrue(practiceTargetIds(s.copy(rulesVersion = 1)).isEmpty())
    }
    @Test fun targetComparisonUsesTheSameCourseTurnAndDoesNotInventAnImprovement() {
        val s = evaluatedFixture().let { base -> base.copy(practiceTargets = listOf("left-01"), previousSessionId = "previous",
            previousTargetStatuses = mapOf("left-01" to "OMISSION"), turns = base.turns.map { it.copy(id = "session:${if (it.direction == TurnDirection.LEFT) "left-01" else "right-01"}") }) }
        assertEquals("OMISSION", practiceTargetOutcomes(s).single().currentStatus)
        val unseen = s.copy(turns = s.turns.map { it.copy(observation = Observation.UNSEEN) })
        assertEquals("HELD", practiceTargetOutcomes(unseen).single().currentStatus)
        assertEquals("OMISSION", practiceTargetOutcomes(unseen).single().previousStatus)
    }
    @Test fun safetyReportAndItsCorrectionPreserveObserverAnswers() {
        val e = PracticeEngine(evaluatedFixture())
        val answers = e.session.turns.map { it.observation }
        e.setSafetyIssue(true)
        assertEquals(answers, e.session.turns.map { it.observation })
        assertFalse(PracticeEvaluator.evaluate(e.session).mistake)
        e.setSafetyIssue(false)
        assertEquals(answers, e.session.turns.map { it.observation })
        assertTrue(PracticeEvaluator.evaluate(e.session).mistake)
    }
    @Test fun twoUnseenAnswersNeverBecomeACompletedAssessment() {
        val s = evaluatedFixture().let { base -> base.copy(turns = base.turns.map { it.copy(observation = Observation.UNSEEN) }) }
        val result = PracticeEvaluator.evaluate(s)
        assertEquals("HELD", result.status); assertEquals(0, result.checked); assertFalse(result.mistake)
    }

    private fun automaticReview(): PracticeSession {
        val session = replay(rules = VEHICLE_REVIEW_RULES)
        val right = session.turns.single { it.direction == TurnDirection.RIGHT }
        return session.copy(samples = session.samples.map {
            if (it.key == "turn" && it.atMs in right.entryMs..right.exitMs!!) it.copy(value = "RIGHT") else it
        })
    }

    @Test fun newPracticeFinishesWithSignalEvidenceAndNoObserverOrDeduction() {
        val s = automaticReview()
        assertEquals(SessionPhase.COMPLETE, s.phase)
        assertTrue(s.turns.all { it.observation == Observation.UNANSWERED })
        val results = PracticeEvaluator.turnAssessments(s)
        assertEquals(listOf("SIGNAL_ABSENT", "OBSERVED"), results.map { it.status })
        assertTrue(results.none { it.mistake })
        assertEquals(listOf(true, false), results.map { it.practiceSuggested })
        val summary = PracticeEvaluator.evaluate(s)
        assertEquals(2, summary.checked); assertNull(summary.score)
        assertTrue(summary.practiceSuggested); assertFalse(summary.mistake)
        assertSame(CourseCatalog.left, CourseCatalog.recommendation(s))
        assertEquals(listOf("left-01"), practiceTargetIds(s.copy(nextPractice = true)))
        val qr = reportText(s)
        assertTrue(qr.contains("차량 신호")); assertTrue(qr.contains("깜빡이가 계속 꺼져"))
        assertFalse(qr.contains("동승자")); assertFalse(qr.contains("감점"))
        val engine = PracticeEngine(s)
        engine.observe(Observation.CONFIRMED)
        assertEquals(s, engine.session)
    }

    @Test fun suggestedPracticeIsComparedWithoutInventingAConfirmation() {
        val s = automaticReview()
        val correctedSignals = s.samples.map {
            if (it.key == "turn" && it.atMs == s.turns.first().entryMs) it.copy(value = "LEFT") else it
        }
        val repeat = s.copy(samples = correctedSignals, practiceTargets = listOf("left-01"),
            previousSessionId = "earlier", previousTargetStatuses = mapOf("left-01" to "SIGNAL_ABSENT"))
        val comparison = practiceTargetOutcomes(repeat).single()
        assertEquals("SIGNAL_ABSENT", comparison.previousStatus)
        assertEquals("OBSERVED", comparison.currentStatus)
        assertFalse(PracticeEvaluator.evaluate(repeat).practiceSuggested)
        assertNull(CourseCatalog.recommendation(repeat))
    }

    @Test fun incompleteConflictingOrUntrustedEvidenceCannotRecommendPractice() {
        val s = automaticReview()
        val event = s.turns.first()
        val from = event.entryMs
        val until = event.exitMs!!
        val broken = listOf(
            s.copy(turns = s.turns.map { if (it == event) it.copy(evidenceStatus = "REJECTED") else it }),
            s.copy(samples = s.samples.filterNot { it.key == "location" && it.atMs in from..until }),
            s.copy(samples = s.samples.filterNot { it.key == "steering_subscription" && it.atMs in from..until }),
            s.copy(samples = s.samples.map { if (it.key == "turn" && it.atMs in from..until) it.copy(value = "RIGHT") else it }),
            s.copy(dataGap = true, evidenceGaps = listOf(EvidenceGap(from, until))),
            s.copy(safetyIssue = true), s.copy(interrupted = true))
        broken.forEach {
            val result = PracticeEvaluator.turnAssessments(it).first()
            assertEquals("HELD", result.status); assertFalse(result.practiceSuggested)
            assertFalse(PracticeEvaluator.evaluate(it).practiceSuggested)
        }
    }

    @Test fun movingUnbuckledSignalIsReportedAlongsideTheIndependentIndicatorRecords() {
        val s = automaticReview()
        val readings = s.samples.map {
            if (it.key == "belt") it.copy(value = "false", quality = "VALID") else it
        }
        val summary = PracticeEvaluator.evaluate(s.copy(samples = readings))
        assertTrue(summary.reason.contains("벨트 미착용")); assertEquals(2, summary.checked)
        assertNull(summary.score); assertFalse(summary.mistake)
        val denied = readings.map { if (it.key == "belt") it.copy(quality = "UNAVAILABLE") else it }
        assertFalse(PracticeEvaluator.evaluate(s.copy(samples = denied)).reason.contains("미착용"))
    }

    @Test fun intermediateBadOrRepeatedLocationFixCannotSupportAutomaticFeedback() {
        val s = automaticReview()
        val event = s.turns.first()
        val location = s.samples.first { it.key == "location" && it.atMs > event.entryMs && it.atMs < event.exitMs!! }
        for (changed in listOf(location.copy(origin = "LAST_KNOWN"), location.copy(value = "37,127,0,90,1000"),
                location.copy(value = "NaN,127,0,5,1000"), location.copy(value = "37,127,0,5,0"))) {
            val samples = s.samples.map { if (it == location) changed else it }
            assertEquals("HELD", PracticeEvaluator.turnAssessments(s.copy(samples = samples)).first().status)
        }
        val repeated = s.samples.map {
            if (it.key == "location" && it.atMs in event.entryMs..event.exitMs!!)
                it.copy(value = it.value!!.substringBeforeLast(',') + ",50000") else it
        }
        assertEquals("HELD", PracticeEvaluator.turnAssessments(s.copy(samples = repeated)).first().status)
        val streamResumedLate = s.samples.map {
            if (it.key == "location" && it.atMs >= location.atMs)
                it.copy(value = it.value!!.substringBeforeLast(',') + "," + (it.value.substringAfterLast(',').toLong() + 4000)) else it
        }
        assertEquals("HELD", PracticeEvaluator.turnAssessments(s.copy(samples = streamResumedLate)).first().status)
    }

    @Test fun allAvailablePracticeTypesProceedStraightToASavedResult() {
        val native = NativeDestination("판교역", PracticeCourse.finish, 3100, 720)
        (CourseCatalog.all + native.route()).forEach { route ->
            val s = PracticeSession(courseId = route.id, rulesVersion = route.rulesVersion, startedAtMs = 1000,
                startedWallMs = 1, expectedDoors = emptyList(), nativeDestination = native.takeIf { route.id == native.route().id })
            assertTrue(s.vehicleReview)
            val e = PracticeEngine(s)
            assertFalse(e.finish(snapshot(2000, PracticeCourse.start, 0.0), 2000))
            assertTrue(e.finish(snapshot(3000, PracticeCourse.start, 0.0, false), 3000))
            assertEquals(SessionPhase.COMPLETE, e.session.phase)
            assertNull(PracticeEvaluator.evaluate(e.session).score)
            assertFalse(PracticeEvaluator.evaluate(e.session).practiceSuggested)
        }
    }

    @Test fun gpsLanePassageDescribesSignalsWithoutAssessingALaneChange() {
        val route = CourseCatalog.lane
        val e = PracticeEngine(PracticeSession(courseId = route.id, rulesVersion = route.rulesVersion, startedAtMs = 1000,
            startedWallMs = 1, expectedDoors = emptyList()))
        var now = 1000L
        for ((a, b) in route.points.zipWithNext()) {
            val count = ceil(distanceMeters(a, b) / 5).toInt()
            repeat(count) { i ->
                val p = GeoPoint(a.lat + (b.lat - a.lat) * i / count, a.lon)
                e.tick(snapshot(now, p, 0.0), now); now += 1000
            }
        }
        assertTrue(e.finish(snapshot(now, route.finish, 0.0, false), now))
        val result = PracticeEvaluator.evaluate(e.session)
        assertNotNull(e.session.passage?.exitMs)
        assertEquals("LANE_SIGNALS", result.status)
        assertTrue(result.reason.contains("계속 꺼져")); assertNull(result.score)
        assertFalse(result.mistake); assertFalse(result.practiceSuggested)
        val lost = e.session.copy(samples = e.session.samples.filterNot { it.key == "turn_subscription" })
        assertEquals("HELD", PracticeEvaluator.evaluate(lost).status)
    }
}
