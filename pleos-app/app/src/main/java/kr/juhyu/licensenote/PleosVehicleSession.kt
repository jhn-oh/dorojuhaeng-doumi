package kr.juhyu.licensenote

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.LightState
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.listener.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** One bind and its callbacks. Old callbacks are ignored once their provider disappears. */
internal class PleosVehicleSession(
    private val vehicle: Vehicle,
    private val main: Handler,
    private val publish: (String, Signal) -> Unit,
    private val guideVisible: () -> Boolean,
    private val recording: () -> Boolean,
) {
    @Volatile private var closed = false
    private val observedKeys = mutableSetOf<String>()
    private var discoveryAnswered = false
    private val discoveryKeys = setOf("belt_capability", "door_capability", "turn_capability", "steering_capability")

    fun start() = vehicle.initialize()

    private fun onInput(action: () -> Unit) {
        if (closed) return
        if (Looper.myLooper() == main.looper) action() else main.post { if (!closed) action() }
    }

    private fun emit(key: String, signal: Signal) = onInput {
        observedKeys.add(key)
        if (key in discoveryKeys && (signal.quality == "VALID" || signal.error?.contains("ERROR_NOT_SUPPORTED") == true)) discoveryAnswered = true
        publish(key, signal)
    }

    private fun send(key: String, value: Any?, source: String, origin: String = "READ") {
        emit(key, Signal(value?.toString(), if (value == null) "UNAVAILABLE" else "VALID",
            source, SystemClock.elapsedRealtime(), origin))
    }
    private fun fail(key: String, error: Exception) {
        emit(key, Signal(quality = "ERROR", source = "SDK", receivedMs = SystemClock.elapsedRealtime(), error = error.message))
    }
    private var nextDiscoveryAt = 0L
    private var beltSubscribed = false
    private var turnSubscribed = false
    private var doorsSubscribed = false
    private var steeringSubscribed = false
    private var beltPending = false
    private var turnPending = false
    private var doorsPending = false
    private var steeringPending = false
    private var beltAttempted = false
    private var turnAttempted = false
    private var steeringAttempted = false
    private val registeredDoors = mutableSetOf<DoorArea>()
    private val guideReadDue = mutableMapOf<String, Long>()
    private val guideRequests = mutableMapOf<String, Long>()
    private var frontWiper: WindowArea? = null
    private var previousTurn: TurnSignalValue? = null
    private var turnOffAt = 0L
    private var turnCallbackVersion = 0L
    private var wiperCallbackVersion = 0L
    private var previousWiper: WiperState? = null
    private var wiperOffAt = 0L
    private val wiperListener = object : WiperStateListener {
        override fun onWiperStateUpdated(state: WiperState?) = onInput {
            wiperCallbackVersion++
            recordWiper(state, "CALLBACK")
        }
        override fun onFailed(e: Exception) = onInput {
            previousWiper = null
            wiperOffAt = 0
            releaseWiper()
            fail("wiper_front", e)
        }
    }

    private fun releaseWiper() {
        wiperCallbackVersion++
        frontWiper?.let { runCatching { vehicle.getWiper().unregisterWiperState(it, wiperListener) } }
        frontWiper = null
        previousWiper = null
        wiperOffAt = 0
    }

    private fun recordWiper(state: WiperState?, origin: String) {
        if (closed || frontWiper == null) return
        val now = SystemClock.elapsedRealtime()
        if (previousWiper == WiperState.OFF && state == WiperState.ON && now - wiperOffAt in 0..5000)
            send("wiper_front_activation", wiperOffAt, "Vehicle/Wiper", origin)
        if (state == WiperState.OFF) wiperOffAt = now
        previousWiper = state
        send("wiper_front", state?.name, "Vehicle/Wiper", origin)
    }
    private fun recordTurn(state: TurnSignalValue?, origin: String) {
        val now = SystemClock.elapsedRealtime()
        if (previousTurn?.name == "OFF" && state?.name in setOf("LEFT", "RIGHT") && now - turnOffAt in 0..5000)
            send("turn_activation", "$turnOffAt,${state?.name}", "Vehicle/TurnSignal", origin)
        if (state?.name == "OFF") turnOffAt = now
        previousTurn = state
        send("turn", state?.name, "Vehicle/TurnSignal", origin)
    }
    @Volatile private var seatArea: SeatArea? = null
    private val doors = java.util.concurrent.ConcurrentHashMap<DoorArea, DoorPositionListener>()
    private fun beltFailed(error: Exception) = onInput {
        beltSubscribed = false
        val quality = when {
            error is SecurityException || error.message == "ERROR_SECURITY_PERMISSION" -> "DENIED"
            error is UnsupportedOperationException || error.message in setOf("ERROR_NOT_SUPPORTED_PROPERTY", "ERROR_NOT_SUPPORTED_AREA") -> "UNSUPPORTED"
            else -> "ERROR"
        }
        emit("belt", Signal(quality = quality, source = "Vehicle/Seat", receivedMs = SystemClock.elapsedRealtime(), error = error.message))
    }
    private fun turnFailed(error: Exception) = onInput {
        turnSubscribed = false
        turnCallbackVersion++
        previousTurn = null
        turnOffAt = 0L
        fail("turn", error)
        fail("turn_subscription", error)
    }
    private fun steeringFailed(error: Exception) = onInput {
        steeringSubscribed = false
        fail("steering", error)
        fail("steering_subscription", error)
    }
    private fun steeringAngle(angle: Float?, origin: String) {
        // Vehicle 2.3.2 reports front-wheel degrees; left is negative, not steering-wheel rotation.
        send("steering", angle?.takeIf { it.isFinite() }, "Vehicle/SteeringWheel front-wheel degrees", origin)
    }
    private fun doorFailed(area: DoorArea, error: Exception) = onInput {
        registeredDoors.remove(area)
        doorsSubscribed = false
        fail("door:" + area.values.joinToString("+") { it.name }, error)
    }
    private val beltListener = object : SeatBeltBuckledStateListener {
        override fun onSeatBeltBuckledStateUpdated(state: Boolean?) = send("belt", state, "Vehicle/Seat", "CALLBACK")
        override fun onFailed(e: Exception) = beltFailed(e)
    }
    private val turnListener = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) = onInput {
            turnCallbackVersion++
            recordTurn(state, "CALLBACK")
        }
        override fun onFailed(e: Exception) = turnFailed(e)
    }
    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = steeringAngle(angle, "CALLBACK")
        override fun onFailed(e: Exception) = steeringFailed(e)
    }
    private fun discover() {
        if (!beltSubscribed && !beltPending) {
            beltPending = true
            runCatching {
                vehicle.getSeat().checkSeatBeltBuckledCapabilities(onComplete = { config, areas -> onInput {
                    beltPending = false
                    send("belt_capability", "${config.accessType}:${areas.joinToString { it.values.joinToString { v -> v.name } }}", "Vehicle/Seat")
                    if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                        beltFailed(UnsupportedOperationException("Seat belt read is not supported"))
                        return@onInput
                    }
                    seatArea = areas.firstOrNull { it.values.contentEquals(arrayOf(SeatValue.ROW_1_LEFT)) }
                    seatArea?.let { area ->
                        if (beltAttempted) runCatching { vehicle.getSeat().unregisterSeatBeltBuckledState(area, beltListener) }
                        beltAttempted = true
                        beltSubscribed = true
                        runCatching { vehicle.getSeat().registerSeatBeltBuckledState(area, beltListener) }.onFailure { beltFailed(Exception(it)) }
                    } ?: beltFailed(UnsupportedOperationException("Driver buckle area unavailable"))
                } }, onFailed = { error -> onInput { beltPending = false; fail("belt_capability", error); beltFailed(error) } })
            }.onFailure { beltPending = false; beltFailed(Exception(it)) }
        }
        if (!doorsSubscribed && !doorsPending) {
            doorsPending = true
            runCatching {
                vehicle.getDoor().checkDoorPositionCapabilities(onComplete = { config, areas -> onInput {
                    doorsPending = false
                    send("door_capability", "${config.accessType}:${areas.keys.joinToString { it.values.joinToString { v -> v.name } }}", "Vehicle/Door")
                    if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                        doorsSubscribed = false
                        fail("door_capability", UnsupportedOperationException("Door read is not supported"))
                        return@onInput
                    }
                    for (area in areas.keys) {
                        if (registeredDoors.contains(area)) continue
                        val key = "door:" + area.values.joinToString("+") { it.name }
                        val existing = doors[area]
                        val listener = existing ?: object : DoorPositionListener {
                            override fun onPositionUpdated(pos: Int?) = send(key, pos, "Vehicle/Door", "CALLBACK")
                            override fun onFailed(e: Exception) = doorFailed(area, e)
                        }
                        doors[area] = listener
                        if (existing != null) runCatching { vehicle.getDoor().unregisterDoorPosition(area, listener) }
                        registeredDoors.add(area)
                        runCatching { vehicle.getDoor().registerDoorPosition(area, listener) }.onFailure { doorFailed(area, Exception(it)) }
                    }
                    doorsSubscribed = areas.isNotEmpty() && registeredDoors.containsAll(areas.keys)
                } }, onFailed = { error -> onInput { doorsPending = false; doorsSubscribed = false; fail("door_capability", error) } })
            }.onFailure { doorsPending = false; doorsSubscribed = false; fail("door_capability", Exception(it)) }
        }
        if (!turnSubscribed && !turnPending) {
            turnPending = true
            send("turn_subscription", "PENDING", "Vehicle/TurnSignal subscription", "SUBSCRIPTION")
            runCatching {
                vehicle.getTurnSignal().checkTurnSignalStateCapabilities(onComplete = { config -> onInput {
                    turnPending = false
                    send("turn_capability", config.accessType.name, "Vehicle/TurnSignal")
                    if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                        turnFailed(UnsupportedOperationException("Turn signal read is not supported"))
                        return@onInput
                    }
                    if (turnAttempted) runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turnListener) }
                    turnAttempted = true
                    turnSubscribed = true
                    runCatching {
                        vehicle.getTurnSignal().registerTurnSignalState(turnListener)
                        if (turnSubscribed) send("turn_subscription", "READY", "Vehicle/TurnSignal subscription", "SUBSCRIPTION")
                    }.onFailure { turnFailed(Exception(it)) }
                } }, onFailed = { error -> onInput { turnPending = false; fail("turn_capability", error); turnFailed(error) } })
            }.onFailure { turnPending = false; turnFailed(Exception(it)) }
        }
        if (!steeringSubscribed && !steeringPending) {
            steeringPending = true
            send("steering_subscription", "PENDING", "Vehicle/SteeringWheel subscription", "SUBSCRIPTION")
            runCatching {
                vehicle.getSteeringWheel().checkSteeringWheelAngleCapabilities(onComplete = { config, bounds -> onInput {
                    steeringPending = false
                    send("steering_capability", "${config.accessType}:${bounds.minValue},${bounds.maxValue}", "Vehicle/SteeringWheel front-wheel degrees")
                    if (config.accessType.name !in setOf("READ", "READ_WRITE")) {
                        steeringFailed(UnsupportedOperationException("Front-wheel steering angle read is not supported"))
                        return@onInput
                    }
                    if (steeringAttempted) runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener) }
                    steeringAttempted = true
                    steeringSubscribed = true
                    runCatching {
                        vehicle.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
                        if (steeringSubscribed) send("steering_subscription", "READY", "Vehicle/SteeringWheel subscription", "SUBSCRIPTION")
                    }.onFailure { steeringFailed(Exception(it)) }
                } }, onFailed = { error -> onInput { steeringPending = false; fail("steering_capability", error); steeringFailed(error) } })
            }.onFailure { steeringPending = false; steeringFailed(Exception(it)) }
        }
    }
    private fun readGuideSignals(now: Long) {
        val guide = guideVisible()
        if (!guide && frontWiper != null) releaseWiper()
        if (!guide && !recording()) {
            guideReadDue.clear()
            guideRequests.clear()
            return
        }
        fun <T> read(key: String, source: String, request: ((T?) -> Unit, (Exception) -> Unit, () -> Unit) -> Unit) {
            if (now < (guideReadDue[key] ?: 0L)) return
            guideRequests[key] = now
            guideReadDue[key] = now + 5000 // Retry if the SDK never completes the request.
            fun finish(signal: Signal, retryMs: Long) = onInput {
                if (guideRequests[key] == now) {
                    guideRequests.remove(key)
                    val received = SystemClock.elapsedRealtime()
                    guideReadDue[key] = received + retryMs
                    emit(key, signal.copy(source = source, receivedMs = received, origin = "READ"))
                }
            }
            val failed: (Exception) -> Unit = { error ->
                // The distributed 2.3.2 AAR exposes Exception; the public symbolic codes
                // survive obfuscation in its message. Other errors stay indeterminate.
                val unsupported = error.message == "ERROR_NOT_SUPPORTED_PROPERTY" || error.message == "ERROR_NOT_SUPPORTED_AREA"
                val denied = error is SecurityException || error.message == "ERROR_SECURITY_PERMISSION"
                finish(Signal(quality = if (unsupported) "UNSUPPORTED" else if (denied) "DENIED" else "ERROR", error = error.message),
                    if (unsupported) 30000 else 4000)
            }
            try {
                request({ value -> finish(Signal(value?.toString(), if (value == null) "UNAVAILABLE" else "VALID"), 1500) },
                    failed, { finish(Signal(quality = "UNSUPPORTED"), 30000) })
            } catch (e: Exception) { failed(e) }
        }
        fun <T> property(key: String, source: String,
                capability: ((CapabilityConfig) -> Unit, (Exception) -> Unit) -> Unit,
                get: ((T?) -> Unit, (Exception) -> Unit) -> Unit) {
            read<T>(key, source) { value, failed, unsupported ->
                capability({ config -> onInput {
                    if (config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) {
                        try { get(value, failed) } catch (e: Exception) { failed(e) }
                    } else unsupported()
                } }, failed)
            }
        }
        if (recording()) {
            val brake = vehicle.getBrake()
            property<Boolean>("brake_pressed", "Vehicle/Brake", brake::checkBrakePedalStatusCapabilities, brake::getCurrentBrakePedalStatus)
            val mode = vehicle.getDrivingMode()
            property<DrivingModeType>("drive_mode", "Vehicle/DrivingMode", mode::checkDrivingModeStateCapabilities, mode::getCurrentDrivingModeState)
            val distance = vehicle.getOdometer()
            property<Float>("odometer_km", "Vehicle/Odometer", distance::checkCurrentDistanceDrivenCapabilities, distance::getCurrentDistanceDriven)
            property<Int>("vehicle_usage_minutes", "Vehicle/Odometer", distance::checkTripCumulativeDrivingTimeCapabilities, distance::getTripCumulativeDrivingTime)
        }
        if (!guide) return
        val fuel = vehicle.getFuel()
        property<Int>("fuel_percent", "Vehicle/Fuel", fuel::checkFuelPercentCapabilities, fuel::getFuelPercent)
        val battery = vehicle.getEvBattery()
        property<Int>("battery_percent", "Vehicle/EvBattery", battery::checkEvBatteryStateOfChargeCapabilities, battery::getEvBatteryStateOfCharge)
        val wiper = vehicle.getWiper()
        read<WiperState>("wiper_front", "Vehicle/Wiper") { value, failed, unsupported ->
            val checkFailed: (Exception) -> Unit = { error -> onInput { releaseWiper(); failed(error) } }
            wiper.checkWiperStateCapabilities({ config, areas -> onInput {
                if (!guideVisible()) return@onInput
                val windshield = areas.keys.firstOrNull { it.values.contentEquals(arrayOf(WindowValue.FRONT_WINDSHIELD)) }
                if (windshield == null || config.accessType !in listOf(AccessType.READ, AccessType.READ_WRITE)) {
                    releaseWiper(); unsupported()
                } else try {
                    if (frontWiper == null) {
                        frontWiper = windshield
                        wiper.registerWiperState(windshield, wiperListener)
                    }
                    val version = wiperCallbackVersion
                    wiper.getCurrentWiperState(windshield, { state -> onInput {
                        // A read queued before a callback must not rewind the observed control state.
                        if (version == wiperCallbackVersion) { recordWiper(state, "READ"); value(state) }
                        else value(previousWiper)
                    } }, checkFailed)
                } catch (e: Exception) { checkFailed(e) }
            } }, checkFailed)
        }
        val safety = vehicle.getSafety()
        read<Boolean>(GUIDE_BELT_WARNING, "Vehicle/Safety") { value, failed, unsupported ->
            safety.checkSeatNotBuckledWarningCapabilities({ config, areas -> onInput {
                val driver = areas.firstOrNull { it.values.contentEquals(arrayOf(SeatValue.ROW_1_LEFT)) }
                if (driver != null && config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) {
                    try { safety.hasSeatBeltNotBuckledWarning(driver, value, failed) } catch (e: Exception) { failed(e) }
                } else unsupported()
            } }, failed)
        }
        read<Boolean>(GUIDE_WASHER_WARNING, "Vehicle/Safety") { value, failed, unsupported ->
            safety.checkLowWasherFluidWarningStateCapabilities({ config ->
                if (config.accessType in listOf(AccessType.READ, AccessType.READ_WRITE)) {
                    try { safety.hasLowWasherFluidWarning(value, failed) } catch (e: Exception) { failed(e) }
                } else unsupported()
            }, failed)
        }
        val light = vehicle.getLight()
        for (key in listOf(GUIDE_LOW_BEAM, GUIDE_HIGH_BEAM)) {
            read<LightState>(key, "Vehicle/Light") { value, failed, unsupported ->
                val checked: (CapabilityConfig, List<LightState>) -> Unit = { config, _ -> onInput {
                    if (config.accessType == AccessType.READ || config.accessType == AccessType.READ_WRITE) {
                        try {
                            if (key == GUIDE_LOW_BEAM) light.getCurrentLowBeamLightState(value, failed)
                            else light.getCurrentHighBeamLightState(value, failed)
                        } catch (e: Exception) { failed(e) }
                    } else unsupported()
                } }
                if (key == GUIDE_LOW_BEAM) light.checkLowBeamLightStateCapabilities(checked, failed)
                else light.checkHighBeamLightStateCapabilities(checked, failed)
            }
        }
    }
    fun poll(now: Long) {
        if (now >= nextDiscoveryAt && (!beltSubscribed || !doorsSubscribed || !turnSubscribed || !steeringSubscribed)) {
            nextDiscoveryAt = now + 5000
            discover()
        }
        // Before a capability callback proves that a connection exists, queue only the
        // bounded discovery requests. Recurring comfort reads must not fill the SDK queue.
        if (discoveryAnswered) readGuideSignals(now)
        seatArea?.let { area -> runCatching { vehicle.getSeat().getCurrentSeatBeltBuckledState(area, { send("belt", it, "Vehicle/Seat") }, { beltFailed(it) }) }.onFailure { beltFailed(Exception(it)) } }
        for (area in doors.keys.toList()) {
            val key = "door:" + area.values.joinToString("+") { it.name }
            runCatching { vehicle.getDoor().getCurrentDoorPosition(area, { send(key, it, "Vehicle/Door") }, { doorFailed(area, it) }) }.onFailure { doorFailed(area, Exception(it)) }
        }
        if (turnSubscribed) runCatching {
            val version = turnCallbackVersion
            vehicle.getTurnSignal().getCurrentTurnSignalState({ onInput {
                if (version == turnCallbackVersion) recordTurn(it, "READ")
            } }, { turnFailed(it) })
        }.onFailure { turnFailed(Exception(it)) }
        if (steeringSubscribed) runCatching {
            vehicle.getSteeringWheel().getCurrentSteeringWheelAngle({ steeringAngle(it, "READ") }, { steeringFailed(it) })
        }.onFailure { steeringFailed(Exception(it)) }
    }

    fun close() {
        closed = true
        val unavailable = Signal(quality = "UNAVAILABLE", source = "Vehicle/Service", receivedMs = SystemClock.elapsedRealtime())
        for (key in observedKeys) publish(key, unavailable)
        // Vehicle.release unregisters every requested listener and clears the SDK's queued calls.
        runCatching { vehicle.release() }
    }
}
