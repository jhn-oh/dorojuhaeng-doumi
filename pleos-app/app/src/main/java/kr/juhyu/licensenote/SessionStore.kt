package kr.juhyu.licensenote

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class PreviousPractice(val title: String, val started: Long, val notes: List<String>)

/** Android's transaction-backed SQLite; all calls belong to the coordinator's I/O thread. */
class SessionStore(context: Context) : SQLiteOpenHelper(context, "practice.db", null, 1) {
    private val persistedCounts = mutableMapOf<String, Int>()
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE sessions (id TEXT PRIMARY KEY, updated_ms INTEGER NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE samples (session_id TEXT NOT NULL, sequence INTEGER NOT NULL, at_ms INTEGER NOT NULL, signal_key TEXT NOT NULL, signal_value TEXT, quality TEXT NOT NULL, source TEXT NOT NULL, origin TEXT NOT NULL, PRIMARY KEY(session_id, sequence))")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unsupported schema migration $oldVersion->$newVersion")
    fun save(session: PracticeSession) {
        val db = writableDatabase
        val count = persistedCounts.getOrPut(session.id) {
            db.rawQuery("SELECT COUNT(*) FROM samples WHERE session_id=?", arrayOf(session.id)).use { it.moveToFirst(); it.getInt(0) }
        }
        check(session.samples.size >= count) { "Cannot truncate persisted evidence" }
        db.beginTransaction()
        try {
            for (i in count until session.samples.size) {
                val s = session.samples[i]
                val values = ContentValues().apply {
                    put("session_id", session.id); put("sequence", i); put("at_ms", s.atMs); put("signal_key", s.key)
                    put("signal_value", s.value); put("quality", s.quality); put("source", s.source); put("origin", s.origin)
                }
                db.insertOrThrow("samples", null, values)
            }
            db.insertWithOnConflict("sessions", null, ContentValues().apply {
                put("id", session.id); put("updated_ms", System.currentTimeMillis()); put("payload", metadata(session).toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        persistedCounts[session.id] = session.samples.size
    }
    fun latest(): PracticeSession? {
        val db = readableDatabase
        val data = db.rawQuery("SELECT payload FROM sessions ORDER BY updated_ms DESC LIMIT 1", null).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null } ?: return null
        return decode(data)
    }

    /** One index page at a time. Revisions replace the same session, including corrected mistakes. */
    fun previous(currentId: String, offset: Int): List<PreviousPractice> {
        val rows = readableDatabase.rawQuery(
            "SELECT payload FROM sessions WHERE id <> ? ORDER BY updated_ms DESC, id DESC LIMIT 8 OFFSET ?",
            arrayOf(currentId, offset.toString())
        ).use { c -> buildList { while (c.moveToNext()) add(JSONObject(c.getString(0))) } }
        return rows.map { data ->
            val session = decode(data)
            val assessment = PracticeEvaluator.evaluate(session)
            if (session.rulesVersion >= PRACTICE_SCORE_RULES) {
                val review = practiceReview(session)
                return@map PreviousPractice(session.practiceRoute.name, session.startedWallMs,
                    listOf(review.score?.let { "종합 연습 점수 ${it}점" } ?: "평가 자료 부족") + review.findings.map { it.title + " · " + it.detail })
            }
            val turns = if (session.rulesVersion >= 2) PracticeEvaluator.turnAssessments(session).filter { session.vehicleReview || it.mistake } else emptyList()
            val notes = if (turns.isEmpty()) listOf(assessment.reason) else (if (session.vehicleReview) listOf(assessment.reason) else emptyList()) + turns.map {
                practiceTargetLabel(it.event.id.substringAfterLast(':'), session.practiceRoute) + " · " + it.reason
            }
            val title = if (session.vehicleReview) session.practiceRoute.name else "${session.practiceRoute.name} · 이전 방식"
            PreviousPractice(title, session.startedWallMs, notes)
        }
    }

    private fun decode(data: JSONObject): PracticeSession {
        val db = readableDatabase
        check(data.getInt("schemaVersion") == 1) { "Unsupported report schema" }
        val id = data.getString("id")
        val samples = mutableListOf<RecordedSignal>()
        db.rawQuery("SELECT at_ms,signal_key,signal_value,quality,source,origin FROM samples WHERE session_id=? ORDER BY sequence", arrayOf(id)).use { c ->
            while (c.moveToNext()) samples += RecordedSignal(c.getLong(0), c.getString(1), if (c.isNull(2)) null else c.getString(2), c.getString(3), c.getString(4), c.getString(5))
        }
        persistedCounts[id] = samples.size
        val p = data.optJSONObject("passage")
        val plannedRoute = data.optJSONObject("plannedRoute")?.let(::parsePlannedRoute)
        check(!data.getString("courseId").startsWith("local-loop-") || plannedRoute != null) {
            "Saved current-location route is missing or invalid"
        }
        return PracticeSession(
            id = id, courseId = data.getString("courseId"), courseVersion = data.getInt("courseVersion"), rulesVersion = data.getInt("rulesVersion"),
            startedAtMs = data.getLong("startedAtMs"), startedWallMs = data.getLong("startedWallMs"),
            endedAtMs = if (data.isNull("endedAtMs")) null else data.getLong("endedAtMs"), phase = SessionPhase.valueOf(data.getString("phase")),
            manualDoor = data.getBoolean("manualDoor"), manualBelt = data.getBoolean("manualBelt"),
            expectedDoors = data.getJSONArray("expectedDoors").let { array -> (0 until array.length()).map { array.getString(it) } },
            samples = samples, passage = p?.let { Passage(it.getString("id"), it.getLong("entryMs"), if (it.isNull("exitMs")) null else it.getLong("exitMs")) },
            observation = Observation.valueOf(data.getString("observation")), interrupted = data.getBoolean("interrupted"), safetyIssue = data.getBoolean("safetyIssue"),
            dataGap = data.getBoolean("dataGap"), nextPractice = data.getBoolean("nextPractice"), revision = data.getInt("revision"),
            evidenceGaps = data.optJSONArray("evidenceGaps")?.let { a -> (0 until a.length()).map { i ->
                val interval = a.getJSONArray(i)
                EvidenceGap(interval.getLong(0), interval.getLong(1))
            } }.orEmpty(),
            turns = data.optJSONArray("turns")?.let { array -> (0 until array.length()).map { index ->
                val t = array.getJSONObject(index)
                TurnEvent(t.getString("id"), TurnDirection.valueOf(t.getString("direction")), t.getLong("entryMs"),
                    if (t.isNull("exitMs")) null else t.getLong("exitMs"),
                    if (t.isNull("entryHeadingDeg")) null else t.getDouble("entryHeadingDeg"),
                    if (t.isNull("headingChangeDeg")) null else t.getDouble("headingChangeDeg"),
                    if (t.isNull("steeringPeakDeg")) null else t.getDouble("steeringPeakDeg"),
                    Observation.valueOf(t.getString("observation")), if (t.isNull("evidenceReason")) null else t.getString("evidenceReason"),
                    t.optString("evidenceStatus", if (t.isNull("exitMs")) "PENDING" else "ESTIMATED"))
            } } ?: emptyList(),
            practiceTargets = data.optJSONArray("practiceTargets")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            previousSessionId = if (data.isNull("previousSessionId")) null else data.getString("previousSessionId"),
            previousTargetStatuses = data.optJSONObject("previousTargetStatuses")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap(),
            nativeDestination = data.optJSONObject("nativeDestination")?.let(NativeDestination::parse),
            plannedRoute = plannedRoute,
            reviewGoals = data.optJSONArray("reviewGoals")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty(),
        )
    }
    fun metadata(s: PracticeSession): JSONObject = JSONObject().put("schemaVersion", 1).put("id", s.id).put("courseId", s.courseId).put("courseVersion", s.courseVersion).put("rulesVersion", s.rulesVersion)
        .apply { if (s.vehicleReview) put("reviewSource", "VEHICLE_SIGNALS") }
        .put("reviewGoals", JSONArray(s.reviewGoals))
        .apply { if (s.rulesVersion >= PRACTICE_SCORE_RULES && s.endedAtMs != null) {
            val review = practiceReview(s)
            put("practiceReview", JSONObject().put("score",review.score ?: JSONObject.NULL).put("checked",JSONArray(review.checked))
                .put("unassessed",JSONArray(review.unassessed)).put("mistakes",JSONArray(review.findings.map { f -> JSONObject()
                    .put("key",f.key).put("title",f.title).put("detail",f.detail).put("atMs",f.atMs).put("penalty",f.penalty).put("goal",f.goal) })))
        } }
        .put("startedAtMs", s.startedAtMs).put("startedWallMs", s.startedWallMs).put("endedAtMs", s.endedAtMs ?: JSONObject.NULL).put("phase", s.phase.name)
        .put("manualDoor", s.manualDoor).put("manualBelt", s.manualBelt).put("expectedDoors", JSONArray(s.expectedDoors)).put("sampleCount", s.samples.size)
        .put("passage", s.passage?.let { JSONObject().put("id", it.id).put("entryMs", it.entryMs).put("exitMs", it.exitMs ?: JSONObject.NULL) } ?: JSONObject.NULL)
        .put("observation", s.observation.name).put("interrupted", s.interrupted).put("safetyIssue", s.safetyIssue).put("dataGap", s.dataGap)
        .put("evidenceGaps", JSONArray(s.evidenceGaps.map { JSONArray(listOf(it.start, it.end)) }))
        .put("nextPractice", s.nextPractice).put("revision", s.revision)
        .put("turns", JSONArray(s.turns.map { t -> JSONObject().put("id", t.id).put("direction", t.direction.name).put("entryMs", t.entryMs)
            .put("exitMs", t.exitMs ?: JSONObject.NULL).put("entryHeadingDeg", t.entryHeadingDeg ?: JSONObject.NULL)
            .put("headingChangeDeg", t.headingChangeDeg ?: JSONObject.NULL).put("steeringPeakDeg", t.steeringPeakDeg ?: JSONObject.NULL)
            .put("observation", t.observation.name).put("evidenceReason", t.evidenceReason ?: JSONObject.NULL).put("evidenceStatus", t.evidenceStatus) }))
        .put("practiceTargets", JSONArray(s.practiceTargets)).put("previousSessionId", s.previousSessionId ?: JSONObject.NULL)
        .put("previousTargetStatuses", JSONObject(s.previousTargetStatuses))
        .put("nativeDestination", s.nativeDestination?.json() ?: JSONObject.NULL)
        .put("plannedRoute", s.plannedRoute?.json() ?: JSONObject.NULL)
}
