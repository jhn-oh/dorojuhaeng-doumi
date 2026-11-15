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

enum class Page { WELCOME, BELT, MIRROR, DOORS, COURSE, DRIVING, RESULT, NOTEBOOK, LESSON, DIAGNOSTICS, GUIDE, SHARE }
enum class CourseStep { LIST, REQUEST, DESTINATION, PREVIEW, UTURN_LESSON, TRAFFIC_LESSON }
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
    val preflightConfirmed: Boolean = false,
    val resumePreviewAfterChecks: Boolean = false,
    val textScale: Float = 1f,
    val guideTopic: GuideTopic? = null,
    val guideReturnPage: Page = Page.WELCOME,
    val courseStep: CourseStep = CourseStep.LIST,
    val selectedRouteId: String = PracticeCourse.id,
    val previewOrigin: CourseStep = CourseStep.LIST,
    val courseRequest: String = "",
    val destinationQuery: String = "",
    val submittedRequest: String? = null,
    val searchStarted: Long = 0,
    val searchResult: NativeDestination? = null,
    val chosenNative: NativeDestination? = null,
    val plannedRoute: PracticeRoute? = null,
    val routeLoading: Boolean = false,
    val routeMessage: String? = null,
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
    private var guideOpenedDirectly = false
    private var savingError = false
    private var loopRequest = 0L
    private val introContentVersion = 1
    // Existing accepted notices carried this same wording before the version field existed.
    private fun acceptedIntroVersion() = preferences.getInt("introVersion", if (preferences.getBoolean("introSeen", false)) 1 else 0)
    internal fun dispatch(action: () -> Unit) {
        if (Looper.myLooper() == main.looper) action() else main.post { action() }
    }
    fun go(page: Page) = dispatch { doGo(page) }
    fun searchNativeDestination() = dispatch {
        val current = state.value
        val now = SystemClock.elapsedRealtime()
        val keyword = current.destinationQuery.trim()
        if (current.page != Page.COURSE || current.courseStep != CourseStep.DESTINATION ||
            keyword.isEmpty() || !canReview(signals(), now)) return@dispatch
        state.value = current.copy(searchStarted = now, searchResult = null, chosenNative = null, error = null)
        runCatching {
            context.startService(Intent(context, TelemetryService::class.java).setAction("SEARCH_DESTINATION").putExtra("query", keyword))
        }.onFailure { state.value = state.value.copy(searchStarted = 0, error = "기본 내비 검색을 열지 못했어요.") }
    }
    fun cancelNativeSearch() = dispatch {
        if (state.value.searchStarted > 0) context.startService(Intent(context, TelemetryService::class.java).setAction("CANCEL_SEARCH"))
        state.value = state.value.copy(searchStarted = 0, searchResult = null, chosenNative = null, error = null)
    }
    fun previewNativeDestination() = dispatch {
        val current = state.value
        val now = SystemClock.elapsedRealtime()
        if (current.page != Page.COURSE || current.courseStep != CourseStep.DESTINATION || !canReview(signals(), now)) return@dispatch
        val destination = selectedNativeDestination(signals(), current.searchStarted, now) ?: return@dispatch
        planLoop(15, destination)
    }
    fun planLocalLoop(minutes: Int = 10) = dispatch { planLoop(minutes.coerceIn(5, 45)) }
    private fun planLoop(minutes: Int, via: NativeDestination? = null) {
        val current = state.value
        val now = SystemClock.elapsedRealtime()
        if (current.page != Page.COURSE || current.routeLoading || !canReview(signals(), now)) return
        val origin = locationPoint(signals()["location"], now)
        if (origin == null) {
            state.value = current.copy(error = "현재 위치를 확인하고 있어요. 위치 권한과 GPS 수신을 확인한 뒤 다시 눌러 주세요.", routeMessage = null)
            return
        }
        val request = ++loopRequest
        state.value = current.copy(routeLoading = true, routeMessage = "현재 위치 주변에서 돌아오는 길을 찾고 있어요.", plannedRoute = null, error = null)
        io.execute {
            val result = runCatching { LocalLoopPlanner.plan(origin, minutes, via) }
            main.post {
                if (loopRequest != request) return@post
                val latest = state.value
                val point = locationPoint(signals()["location"], SystemClock.elapsedRealtime())
                if (latest.page != Page.COURSE || !canReview(signals(), SystemClock.elapsedRealtime()) || point == null || distanceMeters(origin, point) > 100) {
                    state.value = latest.copy(routeLoading = false, routeMessage = null, error = "출발 위치가 바뀌었어요. 주차 후 현재 위치에서 다시 찾아 주세요.")
                    return@post
                }
                result.fold(onSuccess = { route ->
                    state.value = latest.copy(plannedRoute = route, selectedRouteId = route.id, chosenNative = null,
                        searchStarted = 0, searchResult = null, previewOrigin = latest.courseStep,
                        courseStep = CourseStep.PREVIEW, routeLoading = false, routeMessage = null, error = null)
                }, onFailure = {
                    state.value = latest.copy(routeLoading = false, routeMessage = null,
                        error = if (via == null) "약 ${minutes}분에 맞는 주변 코스를 찾지 못했어요. 다른 시간을 선택하거나 다시 시도해 주세요."
                            else "주변에서 돌아오는 길을 찾지 못했어요. 위치와 네트워크를 확인한 뒤 다시 시도해 주세요.")
                })
            }
        }
    }
    fun previousPractices(currentId: String, offset: Int, done: (Result<List<PreviousPractice>>) -> Unit) {
        io.execute {
            val result = runCatching { store.previous(currentId, offset) }
            main.post { done(result) }
        }
    }
    fun courseStep(step: CourseStep) = dispatch {
        val current = state.value
        if (current.page == Page.COURSE && step != CourseStep.PREVIEW && canReview(signals(), SystemClock.elapsedRealtime())) {
            loopRequest++
            state.value = current.copy(courseStep = step, routeLoading = false, routeMessage = null, error = null)
        }
    }
    fun courseInput(text: String, destination: Boolean) = dispatch {
        val current = state.value
        if (current.page != Page.COURSE || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
        state.value = if (destination) current.copy(destinationQuery = text.take(160), searchStarted = 0, searchResult = null, chosenNative = null) else current.copy(courseRequest = text.take(300), submittedRequest = null)
    }
    fun submitCourseRequest(text: String) = dispatch {
        val current = state.value
        if (current.page != Page.COURSE || current.courseStep != CourseStep.REQUEST || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
        state.value = current.copy(courseRequest = text.take(300), submittedRequest = text.take(300))
        val duration = Regex("(\\d+)\\s*분").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 10
        if (duration !in 5..45) state.value = state.value.copy(error = "5~45분 사이로 입력해 주세요.")
        else if (listOf("좌회전만", "우회전만", "넓은", "차로", "차선", "유턴").any { it in text.replace(" ", "") })
            state.value = state.value.copy(error = "지금은 현재 위치에서 돌아오는 길과 예상 시간을 기준으로 찾아요. 특정 회전이나 차로 수 조건은 확인할 수 없어요.")
        else planLoop(duration)
    }
    fun previewCourse(route: PracticeRoute) = dispatch {
        val current = state.value
        if (current.page == Page.COURSE && CourseCatalog.find(route.id) != null && canReview(signals(), SystemClock.elapsedRealtime()))
            planLoop(route.minutes.coerceIn(5, 45))
    }
    fun openGuide(topic: GuideTopic? = null) = dispatch {
        val current = state.value
        if (!current.page.hasGuideEntry() || current.loading || current.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
        guideOpenedDirectly = topic != null
        loopRequest++
        state.value = current.copy(page = Page.GUIDE, guideTopic = topic, guideReturnPage = current.page, routeLoading = false, routeMessage = null)
    }
    fun closeGuide() = dispatch {
        val current = state.value
        if (current.page == Page.GUIDE && canReview(signals(), SystemClock.elapsedRealtime())) {
            state.value = current.copy(page = current.guideReturnPage, guideTopic = null)
        }
    }
    fun openGuideTopic(topic: GuideTopic) = dispatch {
        if (state.value.page == Page.GUIDE && canReview(signals(), SystemClock.elapsedRealtime())) {
            state.value = state.value.copy(guideTopic = topic)
        }
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
    fun setSafetyIssue(reported: Boolean) = dispatch {
        val e = engine ?: return@dispatch
        if (!e.session.vehicleReview || state.value.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return@dispatch
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
                    val page = if (acceptedIntroVersion() != introContentVersion) Page.WELCOME else if (final != null) Page.RESULT else Page.BELT
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
            val search = state.value
            if (search.searchStarted > 0 && search.page == Page.COURSE && search.courseStep in setOf(CourseStep.DESTINATION, CourseStep.PREVIEW)) {
                val failure = signals()["navigation_screen"]?.takeIf { it.receivedMs >= search.searchStarted && (it.quality == "ERROR" || it.value == "SCREEN_BLOCKED_WHILE_MOVING") }
                if (now - search.searchStarted > 600_000 || failure != null) {
                    state.value = search.copy(courseStep = CourseStep.DESTINATION, chosenNative = null, searchStarted = 0, searchResult = null,
                        error = if (failure != null) "기본 내비 검색을 열 수 없어요. 주차 후 다시 시도해 주세요." else "목적지 확인 시간이 지났어요. 다시 검색해 주세요.")
                } else state.value = search.copy(searchResult = selectedNativeDestination(signals(), search.searchStarted, now))
            }
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
        if (state.value.routeLoading) {
            loopRequest++
            state.value = state.value.copy(routeLoading = false, routeMessage = null)
        }
        if (page == Page.BELT || (page == Page.COURSE && state.value.page in setOf(Page.RESULT, Page.NOTEBOOK))) {
            state.value = state.value.copy(page = Page.BELT, manualBelt = false, manualDoor = false, preflightConfirmed = false, resumePreviewAfterChecks = false, error = null)
            return
        }
        state.value = state.value.copy(page = page, error = null)
    }
    private fun doAcceptIntro() = recordIntro("learner")
    private fun doAcceptLicensed() = recordIntro("licensed")
    private fun recordIntro(driverType: String) {
        if (!canReview(signals(), SystemClock.elapsedRealtime())) return
        preferences.edit().putBoolean("introSeen", true).putInt("introVersion", introContentVersion).putString("driverType", driverType).apply()
        go(Page.BELT)
    }
    private fun doConfirmBelt(manual: Boolean) {
        val data = signals()
        val now = SystemClock.elapsedRealtime()
        if (state.value.page != Page.BELT || !canReview(data, now)) return
        val belt = data["belt"].departureBelt(now)
        if (!belt.canProceed(manual)) return
        state.value = state.value.copy(manualBelt = belt == DepartureBelt.UNAVAILABLE, page = Page.MIRROR, error = null)
    }
    private fun doConfirmDoors(manual: Boolean) {
        val current = state.value
        val data = signals()
        val now = SystemClock.elapsedRealtime()
        if (current.page != Page.DOORS || !canReview(data, now) || !departureDoors(data, now).canConfirm(manual)) return
        val confirmed = current.copy(manualDoor = manual, preflightConfirmed = true, page = Page.COURSE, resumePreviewAfterChecks = false, error = null)
        state.value = if (current.resumePreviewAfterChecks) confirmed else confirmed.copy(courseStep = CourseStep.LIST,
            courseRequest = "", submittedRequest = null, destinationQuery = "", searchStarted = 0, searchResult = null, chosenNative = null, plannedRoute = null)
    }
    private fun doOnSignal(key: String, signal: Signal, previous: Signal?) {
        if (key == "belt" && state.value.manualBelt && signal.departureBelt(SystemClock.elapsedRealtime()) == DepartureBelt.UNFASTENED) {
            state.value = state.value.copy(manualBelt = false)
        }
        if (key == "speed" || key == "gear" || key == "ux") closeRestrictedGuideTopic(SystemClock.elapsedRealtime())
        val e = engine ?: return
        e.signalChanged(key, signal, previous)
    }
    private fun closeRestrictedGuideTopic(now: Long) {
        val current = state.value
        if (current.guideTopic != null && guideDrivingConfirmed(signals(), now)) {
            state.value = current.copy(page = if (guideOpenedDirectly) current.guideReturnPage else Page.GUIDE, guideTopic = null)
        }
    }
    private fun doDiagnostics() { pageBeforeDiagnostics = state.value.page; go(Page.DIAGNOSTICS) }
    private fun doProbeNavigation() {
        if (BuildConfig.DEBUG && canReview(signals(), SystemClock.elapsedRealtime())) context.startService(Intent(context, TelemetryService::class.java).setAction("PROBE_NAVIGATION"))
    }
    private fun doBack() {
        if (state.value.routeLoading) {
            loopRequest++
            state.value = state.value.copy(routeLoading = false, routeMessage = null)
        }
        val p = state.value.page
        if (p == Page.COURSE && state.value.courseStep != CourseStep.LIST) {
            val current = state.value
            state.value = current.copy(courseStep = if (current.courseStep == CourseStep.PREVIEW) current.previewOrigin else CourseStep.LIST)
            return
        }
        if (p == Page.GUIDE) {
            // Returning to an in-progress preparation step must preserve its confirmations.
            if (!canReview(signals(), SystemClock.elapsedRealtime())) return
            if (state.value.guideTopic != null && !guideOpenedDirectly) state.value = state.value.copy(guideTopic = null)
            else closeGuide()
            return
        }
        go(when (p) {
            Page.DIAGNOSTICS -> pageBeforeDiagnostics
            Page.NOTEBOOK, Page.SHARE -> Page.RESULT
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
        if (state.value.page != Page.COURSE || state.value.courseStep != CourseStep.PREVIEW) return
        if (!data["belt"].departureBelt(now).canProceed(state.value.manualBelt)) {
            state.value = state.value.copy(page = Page.BELT, manualBelt = false, resumePreviewAfterChecks = true, preflightConfirmed = false,
                error = "출발 전에 운전석 벨트를 다시 확인해 주세요.")
            return
        }
        if (!departureDoors(data, now).canConfirm(state.value.manualDoor)) {
            state.value = state.value.copy(page = Page.DOORS, resumePreviewAfterChecks = true, preflightConfirmed = false,
                error = "출발 전에 문 상태를 다시 확인해 주세요.")
            return
        }
        val doors = data.keys.filter { it.startsWith("door:") }.sorted()
        val turnSubscription = data["turn_subscription"]
        val turnReady = turnSubscription?.quality == "VALID" && turnSubscription.value == "READY"
        val previousEngine = engine
        val previous = previousEngine?.session
        val chosen = state.value.chosenNative
        val active = chosen?.let { selectedNativeDestination(data, state.value.searchStarted, now) }
        if (chosen != null && (active == null || !chosen.samePlace(active))) {
            state.value = state.value.copy(courseStep = CourseStep.DESTINATION, chosenNative = null, error = "기본 내비 목적지가 변경되었거나 확인되지 않았어요. 다시 선택해 주세요.")
            return
        }
        val route = state.value.plannedRoute ?: active?.route() ?: return
        if (route.origin != null) {
            val currentPoint = locationPoint(data["location"], now)
            if (currentPoint == null || distanceMeters(route.origin, currentPoint) > 100) {
                state.value = state.value.copy(error = "현재 위치가 출발 위치와 달라요. 현재 위치에서 코스를 다시 찾아 주세요.")
                return
            }
        }
        val previousTurns = previous?.takeIf { it.rulesVersion >= 2 }?.let(PracticeEvaluator::turnAssessments).orEmpty()
        val targets = previousTurns.filter { it.practiceSuggested }.map { it.event.id.substringAfterLast(':') }
            .filter { id -> route.turns.any { it.id == id } }
        val statuses = previousTurns.filter { it.event.id.substringAfterLast(':') in targets }.associate { it.event.id.substringAfterLast(':') to it.status }
        engine = PracticeEngine(PracticeSession(courseId = route.id, courseVersion = route.version, rulesVersion = route.rulesVersion, startedAtMs = now, startedWallMs = System.currentTimeMillis(), manualDoor = state.value.manualDoor, manualBelt = state.value.manualBelt, expectedDoors = doors, dataGap = !turnReady,
            evidenceGaps = if (turnReady) emptyList() else listOf(EvidenceGap(now, now)),
            practiceTargets = targets, previousSessionId = if (targets.isNotEmpty()) previous?.id else null, previousTargetStatuses = statuses, nativeDestination = active, plannedRoute = state.value.plannedRoute))
        engine!!.tick(data, now)
        try {
            ContextCompat.startForegroundService(context, Intent(context, TelemetryService::class.java).setAction("RECORD").putExtra("prepareCourseRoute", active == null).putExtra("courseId", route.id).putExtra("plannedRoute", state.value.plannedRoute?.json()?.toString()))
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
            state.value = state.value.copy(page = Page.RESULT, session = e.session, assessment = PracticeEvaluator.evaluate(e.session), saving = false)
        }
    }
    private fun doRecommend() {
        val e = engine ?: return
        if (!PracticeEvaluator.evaluate(e.session).practiceSuggested || state.value.saving || !canReview(signals(), SystemClock.elapsedRealtime())) return
        if (!e.session.vehicleReview) { doGo(Page.BELT); return }
        e.session = e.session.copy(nextPractice = true, revision = e.session.revision + 1)
        state.value = state.value.copy(saving = true)
        persist(e.session) { state.value = state.value.copy(page = Page.BELT, session = e.session, assessment = PracticeEvaluator.evaluate(e.session), preflightConfirmed = false, manualBelt = false, manualDoor = false, saving = false) }
    }
    private fun doRetrySave() {
        val e = engine ?: return
        state.value = state.value.copy(saving = true, error = null)
        persist(e.session) {
            val page = if (e.session.phase == SessionPhase.RECORDING) Page.DRIVING else Page.RESULT
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
        val report = store.metadata(session).put("assessment", JSONObject().put("status", a.status).put("score", a.score ?: JSONObject.NULL).put("checked", a.checked).put("mistake", a.mistake).put("practiceSuggested", a.practiceSuggested).put("reason", a.reason))
            .put("turnAssessments", org.json.JSONArray(PracticeEvaluator.turnAssessments(session).map { result -> JSONObject().put("eventId", result.event.id).put("direction", result.event.direction.name).put("status", result.status).put("mistake", result.mistake).put("practiceSuggested", result.practiceSuggested).put("reason", result.reason) }))
            .put("targetOutcomes", org.json.JSONArray(practiceTargetOutcomes(session).map { outcome -> JSONObject().put("courseTurnId", outcome.courseTurnId).put("label", outcome.label).put("previousStatus", outcome.previousStatus ?: JSONObject.NULL).put("currentStatus", outcome.currentStatus) }))
        File(context.filesDir, "last-report.json").writeText(report.toString(2))
    }
}

