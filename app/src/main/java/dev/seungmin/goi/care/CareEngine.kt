package dev.seungmin.goi.care

import android.content.Context
import android.util.Log
import dev.seungmin.goi.Notifier
import dev.seungmin.goi.car.HvacController
import dev.seungmin.goi.car.NaviWatcher
import dev.seungmin.goi.car.PlaceWatcher
import dev.seungmin.goi.car.UpcomingCurve
import dev.seungmin.goi.car.Gear
import dev.seungmin.goi.car.VehicleGateway
import dev.seungmin.goi.car.VehicleSnapshot
import dev.seungmin.goi.data.EventType
import dev.seungmin.goi.data.Place
import dev.seungmin.goi.data.Store
import dev.seungmin.goi.data.TripEvent
import dev.seungmin.goi.data.TripRecord
import dev.seungmin.goi.voice.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class Suggestion(val mode: CareMode, val placeName: String?)

/** 하차 알림. [inTrunk]의 모드는 트렁크에 실은 짐으로 알린다. [modes]는 도착 알림을 켠 모드만, 우선순위 순. */
data class ForgetAlert(val modes: List<CareMode>, val inTrunk: Set<CareMode> = emptySet()) {
    private fun line(m: CareMode) = if (m in inTrunk && m.trunkAlert != null) m.trunkAlert else m.alert

    val headline: String get() = line(modes.first())

    /** 둘째 모드부터의 문장. */
    val others: List<String> get() = modes.drop(1).map(::line).distinct()

    /** 아기·반려동물은 확인 버튼을 눌러야 닫힌다. */
    val mustConfirm: Boolean get() = modes.any { it.mustConfirm }
}

data class Toast(val text: String, val id: Long = System.nanoTime())

data class CareUiState(
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val active: Set<CareMode> = emptySet(),
    /** 가장 최근에 켠 모드. Grid 겹침 화면에서 "이 모드 끄기"에 쓴다. */
    val lastAdded: CareMode? = null,
    val sleeping: Boolean = false,
    val settings: Map<CareMode, ModeSettings> = CareMode.entries.associateWith { it.defaults },
    val places: List<Place> = emptyList(),
    val trips: List<TripRecord> = emptyList(),
    val suggestion: Suggestion? = null,
    val summary: TripRecord? = null,
    val forget: ForgetAlert? = null,
    /** 수면 모드에서 효과음 대신 쌓아 둔 이벤트. 정차하면 보여 준다. */
    val sleepEvents: List<TripEvent> = emptyList(),
    val listening: Boolean = false,
    val toast: Toast? = null,
) {
    val driving: Boolean get() = vehicle.driving

    /** 화면 표시 순서로 정렬한 켜진 모드. */
    val activeModes: List<CareMode> get() = CareMode.displayOrder.filter { it in active }

    /** 켜진 모드를 합친 실제 동작 값. 화면의 "켜진 모드" 표와 엔진이 같은 값을 쓴다. */
    val merged: MergedCare? get() = MergedCare.of(active, settings, vehicle.parked)

    fun settingsOf(mode: CareMode): ModeSettings = settings[mode] ?: mode.defaults
}

/**
 * 고이의 공통 센서 엔진. 모드는 값(임계치·공조 항목)만 넘기고, 동작은 모두 여기서 한다.
 * 주행 중엔 소리로만 개입하고, 멈추면 화면(제안·요약·알림)으로 보여 준다.
 */
class CareEngine(context: Context, private val scope: CoroutineScope) {
    private val app = context.applicationContext
    private val store = Store(app)
    val vehicle = VehicleGateway(app)
    private val hvac = HvacController(vehicle)
    val navi = NaviWatcher(app)
    private val placeWatcher = PlaceWatcher(app, ::onPlaceEntered)
    private val voice = Voice(app)
    private val notifier = Notifier(app)
    private val motion = MotionDetector()

    private val _state = MutableStateFlow(
        CareUiState(
            active = store.loadActive(),
            settings = store.loadSettings(),
            places = store.loadPlaces(),
            trips = store.loadTrips(),
        ),
    )
    val state: StateFlow<CareUiState> = _state.asStateFlow()

    /** 앱 창이 화면에 보이는지. 안 보이면 제안·알림을 시스템 알림으로 띄운다. */
    @Volatile
    var visible: Boolean = false

    private var started = false
    private var trip: TripDraft? = null
    private val sleepQueue = mutableListOf<TripEvent>()
    private var forgetArmed = false
    /** 픽업 때 트렁크에 실은 것으로 보는 모드. 트렁크가 열렸을 때 켜져 있던(또는 직후 켠) 모드다. */
    private val inTrunk = mutableSetOf<CareMode>()
    private var trunkOpenedSinceArrival = false
    private var lastTrunkOpenAt = 0L
    /** 지금 운반을 시작한 픽업 장소(제안을 받아 켰을 때). */
    private var carryFrom: String? = null
    private var curveAnnounced = false
    private var lastCurveDistance = Double.MAX_VALUE
    private var lastHvacFallback = 0L
    private var suggestionTimeout: Job? = null
    private var freshAirJob: Job? = null
    private var appliedKey: Any? = null
    private val collectors = mutableListOf<Job>()

    private class TripDraft(
        var startedAt: Long,
        val modes: MutableSet<CareMode>,
        val from: String?,
        val events: MutableList<TripEvent> = mutableListOf(),
        /** 이번 주행에서 이미 말로 알려 준 이벤트 종류. 처음 한 번만 말하고 그다음은 효과음만 낸다. */
        val spoken: MutableSet<EventType> = mutableSetOf(),
        var distanceM: Double = 0.0,
        var lastSpeedAt: Long = 0L,
        var lastSpeedMps: Float = 0f,
        var restsAnnounced: Int = 0,
    )

    fun start() {
        if (started) return
        started = true
        vehicle.start()
        navi.start()
        placeWatcher.start()
        placeWatcher.watch(_state.value.places)
        voice.onListeningChanged = { listening -> _state.update { it.copy(listening = listening) } }
        voice.start()
        collectors += scope.launch { vehicle.snapshot.collect(::onVehicle) }
        collectors += scope.launch { navi.curve.collect { evaluateCurve(it) } }
        applyHvac()
    }

    fun stop() {
        if (!started) return
        started = false
        collectors.forEach { it.cancel() }
        collectors.clear()
        voice.stop()
        placeWatcher.stop()
        navi.stop()
        vehicle.stop()
    }

    // region 사용자 동작

    fun setMode(mode: CareMode, on: Boolean, announce: Boolean = false) {
        val before = _state.value.active
        val after = if (on) before + mode else before - mode
        if (after == before) {
            if (announce) voice.speak(if (on) "${mode.label} 모드는 켜져 있어요" else "${mode.label} 모드는 꺼져 있어요")
            return
        }
        _state.update {
            it.copy(
                active = after,
                lastAdded = if (on) mode else it.lastAdded?.takeIf { m -> m in after },
                sleeping = it.sleeping && CareMode.BABY in after,
            )
        }
        store.saveActive(after)
        if (on) {
            val now = System.currentTimeMillis()
            if (_state.value.driving && trip == null) trip = newTrip(now, after)
            trip?.modes?.add(mode)
            // 짐을 먼저 싣고 모드를 나중에 켜는 경우: 정차 중 최근에 트렁크를 열었으면 트렁크에 실은 것으로 본다
            val v = _state.value.vehicle
            if (mode.trunkAlert != null && !v.driving && (v.trunkOpen == true || now - lastTrunkOpenAt < TRUNK_RECENT_MS)) inTrunk += mode
            // 공조는 아직 적용 전이라 결과를 단정하지 않는다. 적용 값은 화면의 표가 보여 준다
            if (before.isNotEmpty()) toast("${mode.label} 모드도 켰어요")
        }
        if (announce) voice.speak(if (on) "${mode.label} 모드 켰어요" else "${mode.label} 모드 껐어요")
        if (!on) inTrunk -= mode
        if (after.isEmpty()) endCarry()
        applyHvac()
    }

    fun allOff() {
        _state.update { it.copy(active = emptySet(), lastAdded = null, sleeping = false) }
        store.saveActive(emptySet())
        endCarry()
        applyHvac()
    }

    private fun endCarry() {
        inTrunk.clear()
        carryFrom = null
    }

    fun setSleeping(on: Boolean) {
        if (on && CareMode.BABY !in _state.value.active) setMode(CareMode.BABY, true)
        _state.update { it.copy(sleeping = on) }
    }

    fun updateSettings(mode: CareMode, transform: (ModeSettings) -> ModeSettings) {
        val next = transform(_state.value.settingsOf(mode))
        _state.update { it.copy(settings = it.settings + (mode to next)) }
        store.saveSettings(mode, next)
        applyHvac()
    }

    fun talk() {
        if (_state.value.listening) {
            voice.cancelListening()
            return
        }
        voice.listen { text -> handle(VoiceCommand.parse(text), heard = text != null) }
    }

    fun acceptSuggestion() {
        val s = _state.value.suggestion ?: return
        closeSuggestion()
        if (_state.value.active.isEmpty()) carryFrom = s.placeName
        setMode(s.mode, true)
        voice.speak("${s.mode.label} 모드 켰어요")
    }

    fun dismissSuggestion() = closeSuggestion()

    /** 요약에서 "계속 켜 두기": 모드를 그대로 두고 닫는다(중간에 들른 곳). */
    fun continueCarrying() {
        _state.update { it.copy(summary = null) }
    }

    /** 요약에서 "모드 끄기": 모드를 끄고 공조를 원래대로 돌린다. */
    fun finishCarrying() {
        _state.update { it.copy(summary = null) }
        allOff()
    }

    fun confirmForget() {
        _state.update { it.copy(forget = null) }
        notifier.cancelForget()
    }

    fun confirmSleepEvents() {
        _state.update { it.copy(sleepEvents = emptyList()) }
    }

    fun clearToast(id: Long) {
        _state.update { if (it.toast?.id == id) it.copy(toast = null) else it }
    }

    /** 지금 위치를 픽업 장소로 저장한다. 위치는 차량 안에만 둔다. */
    fun saveHere(mode: CareMode) {
        val loc = placeWatcher.lastLocation
        if (loc == null) {
            toast("지금 위치를 알 수 없어요")
            return
        }
        val n = _state.value.places.count { it.mode == mode } + 1
        val place = Place(UUID.randomUUID().toString(), "${mode.label} 픽업 $n", loc.latitude, loc.longitude, 50, mode, true)
        updatePlaces(_state.value.places + place)
        toast("픽업 장소로 저장했어요")
    }

    fun togglePlace(id: String) =
        updatePlaces(_state.value.places.map { if (it.id == id) it.copy(autoSuggest = !it.autoSuggest) else it })

    fun deletePlace(id: String) = updatePlaces(_state.value.places.filterNot { it.id == id })

    // endregion

    // region 데모·테스트(디버그 빌드의 adb 브로드캐스트에서 호출)

    fun simulateEnter(placeId: String?) {
        val id = placeId ?: _state.value.places.firstOrNull { it.autoSuggest }?.id ?: return
        onPlaceEntered(id)
    }

    fun simulateUtterance(text: String) = handle(VoiceCommand.parse(text), heard = true)

    /** 주행 시간을 [minutes]분 앞당긴다(휴식 안내·운반 시간 확인용). */
    fun simulateElapsed(minutes: Int) {
        trip?.let { it.startedAt -= minutes * 60_000L }
        onVehicle(_state.value.vehicle)
    }

    // endregion

    private fun handle(cmd: VoiceCommand, heard: Boolean) {
        val s = _state.value
        when (cmd) {
            VoiceCommand.Yes -> if (s.suggestion != null) acceptSuggestion() else unknown(heard)
            VoiceCommand.No -> if (s.suggestion != null) dismissSuggestion() else unknown(heard)
            is VoiceCommand.Turn -> when {
                cmd.mode == null -> {
                    allOff()
                    voice.speak("모두 껐어요")
                }
                cmd.on && s.suggestion?.mode == cmd.mode -> acceptSuggestion()
                else -> setMode(cmd.mode, cmd.on, announce = true)
            }
            is VoiceCommand.Sleep -> {
                setSleeping(cmd.on)
                voice.speak(if (cmd.on) "조용히 갈게요" else "수면 모드 껐어요")
            }
            VoiceCommand.Unknown -> unknown(heard)
        }
    }

    private fun unknown(heard: Boolean) {
        val text = if (heard) "다시 말해 주세요" else "못 들었어요"
        toast(text)
        // 주행 중엔 화면을 보지 않으니 소리로도 알린다
        if (_state.value.driving) voice.speak(text)
    }

    private fun newTrip(now: Long, modes: Set<CareMode>) = TripDraft(now, modes.toMutableSet(), carryFrom)

    private fun onVehicle(s: VehicleSnapshot) {
        val prev = _state.value.vehicle
        _state.update { it.copy(vehicle = s) }
        val now = System.currentTimeMillis()
        val active = _state.value.active

        if (s.driving != prev.driving && s.driving) {
            if (trip == null && active.isNotEmpty()) trip = newTrip(now, active)
            forgetArmed = false
            _state.update { it.copy(sleepEvents = emptyList(), summary = null) }
        }
        // 공조는 주차(P) 여부가 바뀔 때만 다시 맞춘다. 신호 대기마다 껐다 켜지 않는다
        if (s.parked != prev.parked) applyHvac()

        val t = trip
        if (active.isNotEmpty() && t != null) {
            integrateDistance(t, s, now)
            val sensitivity = merged()?.sensitivity ?: Sensitivity.NORMAL
            motion.onSample(s.speedKmh, s.steeringDeg, sensitivity, now).forEach { type -> record(type, now) }
            remindRest(t, now)
        } else {
            motion.reset()
        }

        if (!s.driving && _state.value.sleeping && sleepQueue.isNotEmpty() && (s.speedKmh ?: 0f) < 0.5f && !s.parked) {
            _state.update { it.copy(sleepEvents = sleepQueue.toList()) }
            sleepQueue.clear()
        }

        if (s.gear == Gear.P && prev.gear != Gear.P) arrive(now)

        if (!s.driving && s.trunkOpen == true && prev.trunkOpen != true) {
            lastTrunkOpenAt = now
            if (forgetArmed) {
                trunkOpenedSinceArrival = true
            } else {
                inTrunk += active.filter { it.trunkAlert != null }
            }
        }
        if (forgetArmed && s.driverDoorOpen == true && prev.driverDoorOpen != true) raiseForget()

        evaluateCurve(navi.curve.value)
    }

    /** 차속을 시간에 대해 적분해 운반 거리를 구한다. 데모 주입처럼 값이 띄엄띄엄 와도 직전 속도를 유지한 것으로 본다. */
    private fun integrateDistance(t: TripDraft, s: VehicleSnapshot, now: Long) {
        val v = (s.speedKmh ?: return) / 3.6f
        if (t.lastSpeedAt > 0L) t.distanceM += t.lastSpeedMps * (now - t.lastSpeedAt) / 1000.0
        t.lastSpeedAt = now
        t.lastSpeedMps = v
    }

    /** 반려동물 모드: 정한 간격마다 쉬어 가기를 권한다. 정차 중이면 다음 출발 때 말한다. */
    private fun remindRest(t: TripDraft, now: Long) {
        val every = merged()?.restEveryMin ?: return
        val due = ((now - t.startedAt) / (every * 60_000L)).toInt()
        if (due <= t.restsAnnounced || !_state.value.driving) return
        t.restsAnnounced = due
        val hours = (due * every) / 60
        val elapsed = if (hours >= 1 && (due * every) % 60 == 0) "${hours}시간" else "${due * every}분"
        voice.speak("출발한 지 $elapsed 지났어요. 반려동물과 잠깐 쉬어 가세요")
    }

    private fun record(type: EventType, now: Long) {
        val event = TripEvent(type, now)
        trip?.events?.add(event)
        Log.i(TAG, "event $type")
        if (_state.value.sleeping) {
            sleepQueue += event
            return
        }
        if (type == EventType.CURVE_NOTICE || merged()?.feedbackSound == false) return
        voice.beep()
        // 효과음만으로는 무엇이 감지됐는지 알 수 없어서, 주행마다 종류별 처음 한 번은 말로도 알린다
        val t = trip ?: return
        if (t.spoken.add(type)) voice.speak(type.spoken)
    }

    private fun arrive(now: Long) {
        val draft = trip ?: return
        trip = null
        motion.reset()
        val record = TripRecord(
            draft.startedAt, now, CareMode.entries.filter { it in draft.modes }, draft.events.toList(),
            distanceM = draft.distanceM, fromPlace = draft.from,
        )
        val worthShowing = now - draft.startedAt >= MIN_TRIP_MS || draft.events.isNotEmpty()
        if (worthShowing) {
            val trips = listOf(record) + _state.value.trips
            store.saveTrips(trips)
            _state.update { it.copy(trips = trips, summary = record) }
        }
        // 수면 모드는 이번 주행에만 쓴다. 다음 출발 때 다시 "아기 자요"로 켠다
        sleepQueue.clear()
        _state.update { it.copy(sleeping = false, sleepEvents = emptyList()) }
        if (_state.value.active.isNotEmpty()) {
            forgetArmed = true
            trunkOpenedSinceArrival = false
            // 운전석 문 상태를 읽을 수 없는 차에서는 도착(기어 P) 즉시 알린다
            if (_state.value.vehicle.driverDoorOpen == null) raiseForget()
        }
    }

    private fun raiseForget() {
        forgetArmed = false
        val s = _state.value
        val modes = CareMode.entries.filter { it in s.active && s.settingsOf(it).arrivalAlert }
        if (modes.isEmpty()) return
        // 도착한 뒤 이미 트렁크를 열었다면 꺼냈다고 보고 트렁크 문구 대신 일반 문구를 쓴다
        val alert = ForgetAlert(modes, if (trunkOpenedSinceArrival) emptySet() else inTrunk.toSet())
        _state.update { it.copy(forget = alert) }
        voice.speak("${alert.headline}. 내리기 전에 확인해 주세요")
        if (!visible) notifier.showForget(alert)
    }

    private fun evaluateCurve(curve: UpcomingCurve?) {
        if (curve == null) {
            curveAnnounced = false
            lastCurveDistance = Double.MAX_VALUE
            return
        }
        if (curve.distanceM > lastCurveDistance + 50) curveAnnounced = false
        lastCurveDistance = curve.distanceM
        val s = _state.value
        val leadM = merged()?.curveLeadM ?: return
        // 거의 서 있을 때(정체 등)는 알리지 않는다. 차속을 못 읽으면 거리만으로 판단한다
        val slow = s.vehicle.speedKmh?.let { it / 3.6f < MIN_CURVE_SPEED_MPS } ?: false
        if (curveAnnounced || leadM <= 0 || slow) return
        if (curve.distanceM <= leadM) {
            curveAnnounced = true
            voice.speak(if (s.sleeping) "커브, 천천히" else "앞에 커브가 있어요, 천천히")
            record(EventType.CURVE_NOTICE, System.currentTimeMillis())
        }
    }

    private fun onPlaceEntered(placeId: String) {
        val s = _state.value
        val place = s.places.firstOrNull { it.id == placeId } ?: return
        if (place.mode in s.active || s.suggestion != null) return
        _state.update { it.copy(suggestion = Suggestion(place.mode, place.name)) }
        voice.speak("${place.name}에 왔어요. ${place.mode.label} 모드를 켤까요?")
        if (!visible) notifier.showSuggestion(place.mode, place.name)
        suggestionTimeout?.cancel()
        suggestionTimeout = scope.launch {
            delay(TTS_LEAD_MS)
            if (_state.value.suggestion != null && !_state.value.listening) talk()
            delay(SUGGESTION_TIMEOUT_MS - TTS_LEAD_MS)
            closeSuggestion()
        }
    }

    private fun closeSuggestion() {
        suggestionTimeout?.cancel()
        suggestionTimeout = null
        if (_state.value.listening) voice.cancelListening()
        _state.update { it.copy(suggestion = null) }
        notifier.cancelSuggestion()
    }

    private fun updatePlaces(places: List<Place>) {
        _state.update { it.copy(places = places) }
        store.savePlaces(places)
        placeWatcher.watch(places)
    }

    private fun merged(): MergedCare? = _state.value.merged

    /** 켜진 모드·설정·주차 상태가 바뀔 때마다 공조를 다시 맞춘다. */
    private fun applyHvac() {
        val m = merged()
        val key = m?.copy(
            sensitivity = Sensitivity.NORMAL, sensitivityFrom = CareMode.DRINK, curveLeadM = 0,
            feedbackSound = true, restEveryMin = null, hvacItems = emptyList(), hvacWaiting = emptyList(),
        )
        if (key == appliedKey) return
        appliedKey = key
        scope.launch {
            val ok = hvac.apply(m)
            if (!ok) appliedKey = null
            if (!ok && m?.hasHvac == true) hvacFallback(m)
        }
        freshAirJob?.cancel()
        val period = m?.periodicFreshMin
        freshAirJob = if (period != null && !_state.value.vehicle.parked) {
            scope.launch {
                while (true) {
                    delay(period * 60_000L)
                    hvac.freshAirPulse(true)
                    delay(FRESH_AIR_PULSE_MS)
                    hvac.freshAirPulse(false)
                }
            }
        } else {
            null
        }
    }

    /** 공조 제어 권한이 없으면 자동 적용 대신 짧은 음성 권유로 줄인다. 같은 안내는 10분 안에 반복하지 않는다. */
    private fun hvacFallback(m: MergedCare) {
        val now = System.currentTimeMillis()
        if (now - lastHvacFallback < HVAC_FALLBACK_INTERVAL_MS) return
        lastHvacFallback = now
        val text = when {
            m.freshAir == true -> "외기로 바꿔 보세요"
            m.rearTempC != null -> "뒷좌석 온도를 ${m.rearTempC.toInt()}도로 맞춰 보세요"
            m.tempDelta != null -> "실내 온도를 조금 낮춰 보세요"
            m.fanDelta != null && m.fanDelta < 0 -> "바람을 조금 줄여 보세요"
            else -> "공조를 직접 맞춰 주세요"
        }
        voice.speak(text)
    }

    /** 토스트는 사용자가 한 행동의 결과에만 쓴다. 앱이 먼저 건네는 권유·경고는 음성으로 한다. */
    private fun toast(text: String) {
        _state.update { it.copy(toast = Toast(text)) }
    }

    private companion object {
        const val TAG = "GoiEngine"
        const val MIN_TRIP_MS = 20_000L
        const val MIN_CURVE_SPEED_MPS = 20 / 3.6f
        const val TTS_LEAD_MS = 3_500L
        const val SUGGESTION_TIMEOUT_MS = 12_000L
        const val FRESH_AIR_PULSE_MS = 60_000L
        const val HVAC_FALLBACK_INTERVAL_MS = 10 * 60_000L
        const val TRUNK_RECENT_MS = 10 * 60_000L
    }
}
