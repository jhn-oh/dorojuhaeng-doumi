package kr.juhyu.licensenote

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.HandlerThread
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

enum class Page { WELCOME, BELT, MIRROR, DOORS, COURSE, DRIVING, REVIEW, RESULT, NOTEBOOK, LESSON, DIAGNOSTICS, GUIDE, SHARE }
enum class CourseStep { LIST, REQUEST, DESTINATION, PREVIEW }
data class PracticeUi(
    val page: Page = Page.WELCOME,
    val session: PracticeSession? = null,
    val assessment: Assessment? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val error: String? = null,
    val manualBelt: Boolean = false,
    val manualDoor: Boolean = false,
    val now: Long = SystemClock.elapsedRealtime(),
    val reviewEventId: String? = null,
    val preflightConfirmed: Boolean = false,
    val textScale: Float = 1f,
    val guideTopic: GuideTopic? = null,
    val courseStep: CourseStep = CourseStep.LIST,
    val selectedRouteId: String = PracticeCourse.id,
    val previewOrigin: CourseStep = CourseStep.LIST,
    val courseRequest: String = "",
    val destinationQuery: String = "",
    val submittedRequest: String? = null,
)
class PracticeCoordinator(private val context: Context, private val signals: () -> Map<String, Signal>, private val publishSignals: () -> Unit) {
    private val stateThread = HandlerThread("PracticeState").apply { start() }
    private val main = Handler(stateThread.looper)
    private val io = Executors.newSingleThreadExecutor()
    private val store = SessionStore(context)
    private val preferences = context.getSharedPreferences("practice_preferences", Context.MODE_PRIVATE)
    private fun storedTextScale(): Float = preferences.getFloat("ui_text_scale", 1f).takeIf { it == 1f || it == 1.3f } ?: 1f
    private val state = MutableStateFlow(PracticeUi(textScale = storedTextScale()))
    val ui = state.asStateFlow()
    private var engine: PracticeEngine? = null
    private var pageBeforeDiagnostics = Page.COURSE
    private var pageBeforeGuide = Page.WELCOME
    private var savingError = false
    internal fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }
    fun go(page: Page) = dispatch { doGo(page) }
    fun previousPractices(currentId: String, offset: Int, done: (Result<List<PreviousPractice>>) -> Unit) {
        io.execute {
            val result = runCatching { store.previous(currentId, offset) }
            main.post { done(result) }
        }
    }
    fun courseStep(step: CourseStep) = dispatch {
        val current = state.value
        if (current.page == Page.COURSE && step != CourseStep.PREVIEW && canReview(signals(), SystemClock.elapsedRealtime()))
            state.value = current.copy(courseStep = step, error = null)
    }
    fun courseInput(text: String, destination: Boolean) = dispatch {
        val current = state.value
        if (current.page != Page.COURSE || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
        state.value = if (destination) current.copy(destinationQuery = text.take(300)) else current.copy(courseRequest = text.take(300), submittedRequest = null)
    }
    fun submitCourseRequest(text: String) = dispatch {
        val current = state.value
        if (current.page == Page.COURSE && current.courseStep == CourseStep.REQUEST && canReview(signals(), SystemClock.elapsedRealtime()))
            state.value = current.copy(courseRequest = text.take(300), submittedRequest = text.take(300))
    }
    fun previewCourse(route: PracticeRoute) = dispatch {
        val current = state.value
        if (current.page == Page.COURSE && CourseCatalog.find(route.id) != null && canReview(signals(), SystemClock.elapsedRealtime()))
            state.value = current.copy(selectedRouteId = route.id, previewOrigin = current.courseStep, courseStep = CourseStep.PREVIEW, error = null)
    }
    fun openGuide() = dispatch {
        val current = state.value
        if (!current.page.hasGuideEntry() || current.loading || current.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
        pageBeforeGuide = current.page
        state.value = current.copy(page = Page.GUIDE, guideTopic = null)
    }
    fun openGuideTopic(topic: GuideTopic) = dispatch {
        if (state.value.page == Page.GUIDE && canReview(signals(), SystemClock.elapsedRealtime())) {
            state.value = state.value.copy(guideTopic = topic)
        }
    }
    fun review(eventId: String) = dispatch {
        if (canReview(signals(), SystemClock.elapsedRealtime()) && engine?.session?.turns?.any { it.id == eventId && it.exitMs != null } == true)
            state.value = state.value.copy(page = Page.REVIEW, reviewEventId = eventId)
    }
    fun acceptIntro() = dispatch { doAcceptIntro() }
    fun acceptLicensed() = dispatch { doAcceptLicensed() }
    fun confirmBelt(manual: Boolean) = dispatch { doConfirmBelt(manual) }
    fun confirmDoors(manual: Boolean) = dispatch { doConfirmDoors(manual) }
    fun onSignal(key: String, signal: Signal, previous: Signal?) = dispatch { doOnSignal(key, signal, previous) }
    fun diagnostics() = dispatch { doDiagnostics() }
    fun probeNavigation() = dispatch { doProbeNavigation() }
    fun openNavigation() = dispatch {
        runCatching {
            context.startService(Intent(context, TelemetryService::class.java).setAction("OPEN_NAVIGATION").putExtra("withCourseRoute", false))
        }.onFailure { state.value = state.value.copy(error = "기본 내비를 열지 못했습니다. 다시 시도해 주세요.") }
    }
    fun openNavigationSettings() = dispatch {
        if (!BuildConfig.DEBUG || state.value.loading || state.value.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
        runCatching {
            context.startService(Intent(context, TelemetryService::class.java).setAction("OPEN_NAVIGATION_SETTINGS"))
        }.onFailure { state.value = state.value.copy(error = "기본 내비 설정을 열지 못했습니다. 다시 시도해 주세요.") }
    }
    fun back() = dispatch { doBack() }
    fun start() = dispatch { doStart() }
    fun finish() = dispatch { doFinish() }
    fun observe(answer: Observation, safetyIssue: Boolean = false) = dispatch { doObserve(answer, safetyIssue) }
    fun setSafetyIssue(reported: Boolean) = dispatch {
        val e = engine ?: return@dispatch
        if (state.value.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
        e.setSafetyIssue(reported)
        state.value = state.value.copy(saving = true)
        persist(e.session) { state.value = state.value.copy(session = e.session, assessment = PracticeEvaluator.evaluate(e.session), saving = false) }
    }
    fun setTextScale(scale: Float) = dispatch {
        if (scale !in setOf(1f, 1.3f) || state.value.loading || state.value.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
        preferences.edit().putFloat("ui_text_scale", scale).apply()
        state.value = state.value.copy(textScale = scale)
    }
    fun recommend() = dispatch { doRecommend() }
    fun retrySave() = dispatch { doRetrySave() }
    fun recordingFailed() = dispatch {
        val e = engine
        if (e != null) {
            e.session = e.session.copy(phase = SessionPhase.INTERRUPTED, interrupted = true, revision = e.session.revision + 1)
            state.value = state.value.copy(page = Page.RESULT, session = e.session, assessment = PracticeEvaluator.evaluate(e.session), saving = false, error = "주행 기록을 시작하지 못했습니다. 위치 권한을 확인하세요.")
            persist(e.session)
        }
    }
    init {
        io.execute {
            runCatching { store.latest() }.fold(onSuccess = { loaded ->
                var restored = loaded
                if (restored?.phase == SessionPhase.RECORDING) {
                    restored = restored.copy(phase = SessionPhase.INTERRUPTED, interrupted = true, revision = restored.revision + 1)
                    store.save(restored)
                }
                val final = restored
                if (final != null) exportReport(final)
                main.post {
                    engine = final?.let(::PracticeEngine)
                    val page = if (final != null) Page.RESULT else if (preferences.getBoolean("introSeen", false)) Page.BELT else Page.WELCOME
                    state.value = state.value.copy(page = page, session = final, assessment = final?.let(PracticeEvaluator::evaluate), loading = false)
                }
            }, onFailure = { error -> main.post { state.value = state.value.copy(loading = false, error = "저장한 기록을 열지 못했습니다. 앱을 다시 열어 주세요.") } })
        }
        main.post(ticker)
    }
    private val ticker: Runnable get() = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            publishSignals()
            closeRestrictedGuideTopic(now)
            val e = engine
            if (e?.session?.phase == SessionPhase.RECORDING) {
                e.tick(signals(), now)
                state.value = state.value.copy(session = e.session, now = now)
                persist(e.session)
            } else state.value = state.value.copy(now = now)
            main.postDelayed(this, 1000)
        }
    }
    private fun doGo(page: Page) {
        if (!canReview(signals(), SystemClock.elapsedRealtime()) && page != Page.DRIVING) return
        if (page == Page.BELT || (page == Page.COURSE && state.value.page in setOf(Page.RESULT, Page.NOTEBOOK))) {
            state.value = state.value.copy(page = Page.BELT, manualBelt = false, manualDoor = false, preflightConfirmed = false, error = null)
            return
        }
        state.value = state.value.copy(page = page, error = null, reviewEventId = if (page == Page.REVIEW) engine?.session?.turns?.firstOrNull { it.exitMs != null }?.id else null)
    }
    private fun doAcceptIntro() { preferences.edit().putBoolean("introSeen", true).putString("driverType", "learner").apply(); go(Page.BELT) }
    private fun doAcceptLicensed() { preferences.edit().putBoolean("introSeen", true).putString("driverType", "licensed").apply(); go(Page.BELT) }
    private fun doConfirmBelt(manual: Boolean) { state.value = state.value.copy(manualBelt = manual); go(Page.MIRROR) }
    private fun doConfirmDoors(manual: Boolean) { state.value = state.value.copy(manualDoor = manual, preflightConfirmed = true, courseStep = CourseStep.LIST, courseRequest = "", submittedRequest = null, destinationQuery = ""); go(Page.COURSE) }
    private fun doOnSignal(key: String, signal: Signal, previous: Signal?) {
        if (key == "speed" || key == "gear" || key == "ux") closeRestrictedGuideTopic(SystemClock.elapsedRealtime())
        val e = engine ?: return
        e.signalChanged(key, signal, previous)
    }
    private fun closeRestrictedGuideTopic(now: Long) {
        if (state.value.guideTopic != null && !canReview(signals(), now)) state.value = state.value.copy(guideTopic = null)
    }
    private fun doDiagnostics() { pageBeforeDiagnostics = state.value.page; go(Page.DIAGNOSTICS) }
    private fun doProbeNavigation() {
        if (BuildConfig.DEBUG && canReview(signals(), SystemClock.elapsedRealtime())) context.startService(Intent(context, TelemetryService::class.java).setAction("PROBE_NAVIGATION"))
    }
    private fun doBack() {
        val p = state.value.page
        if (p == Page.COURSE && state.value.courseStep != CourseStep.LIST) {
            val current = state.value
            state.value = current.copy(courseStep = if (current.courseStep == CourseStep.PREVIEW) current.previewOrigin else CourseStep.LIST)
            return
        }
        if (p == Page.GUIDE) {
            // Returning to an in-progress preparation step must preserve its confirmations.
            state.value = if (state.value.guideTopic != null) state.value.copy(guideTopic = null)
                else state.value.copy(page = pageBeforeGuide)
            return
        }
        go(when (p) {
            Page.DIAGNOSTICS -> pageBeforeDiagnostics
            Page.NOTEBOOK, Page.REVIEW, Page.SHARE -> Page.RESULT
            Page.LESSON -> Page.NOTEBOOK
            Page.COURSE -> Page.DOORS
            Page.DOORS -> Page.MIRROR
            Page.MIRROR -> Page.BELT
            Page.BELT -> if (engine?.session?.phase in setOf(SessionPhase.COMPLETE, SessionPhase.REVIEW, SessionPhase.INTERRUPTED)) Page.RESULT else Page.WELCOME
            else -> Page.COURSE
        })
    }
    private fun doStart() {
        val now = SystemClock.elapsedRealtime()
        if (state.value.loading || state.value.saving || savingError || !canReview(signals(), now)) return
        if (engine?.session?.phase == SessionPhase.RECORDING) return
        if (!state.value.preflightConfirmed) {
            state.value = state.value.copy(page = Page.BELT, manualBelt = false, manualDoor = false, error = null)
            return
        }
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            state.value = state.value.copy(error = "위치 권한이 필요합니다. 권한을 허용하고 다시 시작하세요.")
            return
        }
        val data = signals()
        val doors = data.keys.filter { it.startsWith("door:") }.sorted()
        val turnSubscription = data["turn_subscription"]
        val turnReady = turnSubscription?.quality == "VALID" && turnSubscription.value == "READY"
        val previousEngine = engine
        val previous = previousEngine?.session
        if (state.value.page != Page.COURSE || state.value.courseStep != CourseStep.PREVIEW) return
        val route = CourseCatalog.find(state.value.selectedRouteId) ?: return
        val targets = previous?.takeIf { it.rulesVersion >= 2 }?.let(PracticeEvaluator::turnAssessments).orEmpty()
            .filter { it.mistake }.map { it.event.id.substringAfterLast(':') }.filter { id -> route.turns.any { it.id == id } }
        val statuses = previous?.let(PracticeEvaluator::turnAssessments).orEmpty().filter { it.event.id.substringAfterLast(':') in targets }.associate { it.event.id.substringAfterLast(':') to it.status }
        engine = PracticeEngine(PracticeSession(courseId = route.id, courseVersion = route.version, rulesVersion = route.rulesVersion, startedAtMs = now, startedWallMs = System.currentTimeMillis(), manualDoor = state.value.manualDoor, manualBelt = state.value.manualBelt, expectedDoors = doors, dataGap = !turnReady,
            evidenceGaps = if (turnReady) emptyList() else listOf(EvidenceGap(now, now)),
            practiceTargets = targets, previousSessionId = if (targets.isNotEmpty()) previous?.id else null, previousTargetStatuses = statuses))
        engine!!.tick(data, now)
        try {
            ContextCompat.startForegroundService(context, Intent(context, TelemetryService::class.java).setAction("RECORD").putExtra("prepareCourseRoute", true).putExtra("courseId", route.id))
            state.value = state.value.copy(page = Page.DRIVING, session = engine!!.session, assessment = null, error = null, preflightConfirmed = false, manualBelt = false, manualDoor = false)
            persist(engine!!.session)
        } catch (error: Exception) {
            engine = previousEngine
            state.value = state.value.copy(error = "주행 기록을 시작하지 못했습니다. 위치 권한을 확인하세요.")
        }
    }
    private fun doFinish() {
        val e = engine ?: return
        if (state.value.saving || !e.finish(signals(), SystemClock.elapsedRealtime())) return
        context.startService(Intent(context, TelemetryService::class.java).setAction("STOP_RECORD"))
        state.value = state.value.copy(saving = true)
        persist(e.session) {
            val page = if (e.session.passage?.exitMs != null || e.session.turns.any { it.exitMs != null }) Page.REVIEW else Page.RESULT
            state.value = state.value.copy(page = page, session = e.session, assessment = PracticeEvaluator.evaluate(e.session), saving = false, reviewEventId = e.session.turns.firstOrNull { it.exitMs != null }?.id)
        }
    }
    private fun doObserve(answer: Observation, safetyIssue: Boolean = false) {
        val e = engine ?: return
        if (state.value.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return
        e.observe(answer, safetyIssue, state.value.reviewEventId)
        state.value = state.value.copy(saving = true)
        persist(e.session) { state.value = state.value.copy(page = if (e.session.phase == SessionPhase.REVIEW) Page.REVIEW else Page.RESULT, session = e.session, assessment = PracticeEvaluator.evaluate(e.session), saving = false, reviewEventId = e.session.turns.firstOrNull { it.exitMs != null && it.observation == Observation.UNANSWERED }?.id) }
    }
    private fun doRecommend() {
        val e = engine ?: return
        if (!PracticeEvaluator.evaluate(e.session).mistake || state.value.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return
        if (e.session.rulesVersion < 2) { doGo(Page.COURSE); return }
        e.session = e.session.copy(nextPractice = true, revision = e.session.revision + 1)
        state.value = state.value.copy(saving = true)
        persist(e.session) { state.value = state.value.copy(page = Page.BELT, session = e.session, assessment = PracticeEvaluator.evaluate(e.session), preflightConfirmed = false, manualBelt = false, manualDoor = false, saving = false) }
    }
    private fun doRetrySave() {
        val e = engine ?: return
        state.value = state.value.copy(saving = true, error = null)
        persist(e.session) {
            val page = if (e.session.phase == SessionPhase.RECORDING) Page.DRIVING else if (e.session.phase == SessionPhase.REVIEW && (e.session.passage?.exitMs != null || e.session.turns.any { it.exitMs != null })) Page.REVIEW else Page.RESULT
            state.value = state.value.copy(page = page, session = e.session, assessment = PracticeEvaluator.evaluate(e.session), saving = false)
        }
    }
    private fun persist(session: PracticeSession, then: (() -> Unit)? = null) {
        io.execute {
            try {
                store.save(session)
                exportReport(session)
                main.post { savingError = false; then?.invoke() }
            } catch (error: Exception) {
                main.post { savingError = true; state.value = state.value.copy(saving = false, error = "기록을 저장하지 못했습니다. 저장 공간을 확인하고 다시 저장하세요.") }
            }
        }
    }
    private fun exportReport(session: PracticeSession) {
        if (!BuildConfig.DEBUG) return
        val a = PracticeEvaluator.evaluate(session)
        val report = store.metadata(session).put("assessment", JSONObject().put("status", a.status).put("score", a.score ?: JSONObject.NULL).put("checked", a.checked).put("mistake", a.mistake).put("reason", a.reason))
            .put("turnAssessments", org.json.JSONArray(PracticeEvaluator.turnAssessments(session).map { result -> JSONObject().put("eventId", result.event.id).put("direction", result.event.direction.name).put("status", result.status).put("mistake", result.mistake).put("reason", result.reason) }))
            .put("targetOutcomes", org.json.JSONArray(practiceTargetOutcomes(session).map { outcome -> JSONObject().put("courseTurnId", outcome.courseTurnId).put("label", outcome.label).put("previousStatus", outcome.previousStatus ?: JSONObject.NULL).put("currentStatus", outcome.currentStatus) }))
        File(context.filesDir, "last-report.json").writeText(report.toString(2))
    }
}

