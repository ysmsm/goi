package dev.seungmin.goi.car

import ai.pleos.playground.vehicle.api.HVAC
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import android.util.Log
import dev.seungmin.goi.care.MergedCare
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 모드의 공조 프리셋을 HVAC에 한 번에 적용하고, 모드가 모두 꺼지면 운전자가 쓰던 값으로 되돌린다.
 * 모든 쓰기는 checkXxxCapabilities로 WRITE 권한을 확인한 영역에만 한다.
 */
class HvacController(private val gateway: VehicleGateway) {
    private data class Zones(
        val recirc: SeatArea?,
        val fan: SeatArea?,
        val fanRange: IntRange?,
        val driverTemp: SeatArea?,
        val rearTemp: SeatArea?,
        val tempRange: ClosedFloatingPointRange<Float>?,
    )

    private data class Saved(val recirc: Boolean?, val fan: Int?, val driverTemp: Float?, val rearTemp: Float?)

    private val mutex = Mutex()
    private var zones: Zones? = null
    private var saved: Saved? = null
    private var applied: MergedCare? = null

    /** @return false면 차량이 해당 공조 제어를 허용하지 않은 것이다(권한·미지원). */
    suspend fun apply(target: MergedCare?): Boolean = mutex.withLock {
        val hvac = gateway.sdk?.getHVAC() ?: return@withLock false
        val want = target?.takeIf { it.hasHvac }
        if (want == applied) return@withLock true
        if (want == null) {
            val ok = restore(hvac)
            applied = null
            return@withLock ok
        }
        val z = zones ?: discover(hvac).also { zones = it }
        if (saved == null) saved = read(hvac, z)
        val base = saved!!
        var ok = true

        // 외기 유입: 원하면 내기 순환을 끄고, 이번에 원하지 않으면 원래대로 둔다
        if (z.recirc != null) {
            val recirc = if (want.freshAir == true) false else base.recirc
            if (recirc != null) ok = ok and set { o, f -> hvac.setHvacRecirculation(z.recirc, recirc, { _, _ -> o() }, f) }
        } else if (want.freshAir == true) ok = false

        val fanBase = base.fan
        if (want.fanDelta != null && z.fan != null && fanBase != null) {
            val range = z.fanRange ?: 0..8
            val target = (maxOf(fanBase, 2) + want.fanDelta).coerceIn(maxOf(range.first, 1), range.last)
            ok = ok and set { o, f -> hvac.setHvacFanSpeed(z.fan, target, { _, _ -> o() }, f) }
        } else if (want.fanDelta != null) ok = false

        val tempArea = if (want.rearTempC != null) z.rearTemp ?: z.driverTemp else z.driverTemp
        val tempTarget = want.rearTempC ?: base.driverTemp?.let { t -> want.tempDelta?.let { t + it } }
        if (tempTarget != null && tempArea != null) {
            val t = z.tempRange?.let { tempTarget.coerceIn(it) } ?: tempTarget
            ok = ok and set { o, f -> hvac.setHvacTemperature(tempArea, t, TemperatureUnit.CELSIUS, { _, _ -> o() }, f) }
        } else if (want.rearTempC != null || want.tempDelta != null) ok = false

        // 실패하면 기록하지 않아 다음 상태 변화 때 다시 시도한다(예: 공조 전원이 켜진 뒤)
        applied = if (ok) want else null
        ok
    }

    /** 주기적 외기 환기: 잠깐 외기로 돌렸다가 지금 적용 중인 값으로 돌아간다. */
    suspend fun freshAirPulse(on: Boolean): Boolean = mutex.withLock {
        val hvac = gateway.sdk?.getHVAC() ?: return@withLock false
        val area = (zones ?: discover(hvac).also { zones = it }).recirc ?: return@withLock false
        val recirc = if (on) false else (if (applied?.freshAir == true) false else saved?.recirc ?: true)
        set { o, f -> hvac.setHvacRecirculation(area, recirc, { _, _ -> o() }, f) }
    }

    private suspend fun restore(hvac: HVAC): Boolean {
        val z = zones ?: return true
        val s = saved ?: return true
        var ok = true
        if (z.recirc != null && s.recirc != null) ok = ok and set { o, f -> hvac.setHvacRecirculation(z.recirc, s.recirc, { _, _ -> o() }, f) }
        if (z.fan != null && s.fan != null) ok = ok and set { o, f -> hvac.setHvacFanSpeed(z.fan, s.fan, { _, _ -> o() }, f) }
        if (z.driverTemp != null && s.driverTemp != null) {
            ok = ok and set { o, f -> hvac.setHvacTemperature(z.driverTemp, s.driverTemp, TemperatureUnit.CELSIUS, { _, _ -> o() }, f) }
        }
        if (z.rearTemp != null && s.rearTemp != null) {
            ok = ok and set { o, f -> hvac.setHvacTemperature(z.rearTemp, s.rearTemp, TemperatureUnit.CELSIUS, { _, _ -> o() }, f) }
        }
        saved = null
        return ok
    }

    private suspend fun discover(hvac: HVAC): Zones {
        val recirc = get<SeatArea?> { o, f ->
            hvac.checkHvacRecirculationCapabilities({ config, areas -> o(if (config.writable) areas.firstOrNull() else null) }, f)
        }
        val fan = get<Pair<SeatArea, IntRange>?> { o, f ->
            hvac.checkHvacFanSpeedCapabilities({ config, map ->
                val e = map.entries.firstOrNull()
                o(if (config.writable && e != null) e.key to (e.value.minValue..e.value.maxValue) else null)
            }, f)
        }
        val temps = get<Pair<Map<SeatArea, ClosedFloatingPointRange<Float>>, Boolean>?> { o, f ->
            hvac.checkHvacTemperatureCapabilities({ config, map ->
                o(map.mapValues { (_, b) -> b.minValue..b.maxValue } to config.writable)
            }, f)
        }
        val tempMap = temps?.takeIf { it.second }?.first.orEmpty()
        val driver = tempMap.keys.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: tempMap.keys.firstOrNull()
        val rear = tempMap.keys.firstOrNull { a -> a.values.any { it == SeatValue.ROW_2_LEFT || it == SeatValue.ROW_2_RIGHT || it == SeatValue.ROW_2_CENTER } }
        return Zones(
            recirc = recirc,
            fan = fan?.first,
            fanRange = fan?.second,
            driverTemp = driver,
            rearTemp = rear,
            tempRange = driver?.let { tempMap[it] },
        ).also { Log.i(TAG, "zones $it") }
    }

    private suspend fun read(hvac: HVAC, z: Zones) = Saved(
        recirc = z.recirc?.let { a -> get<Boolean?> { o, f -> hvac.getHvacRecirculationState(a, { o(it) }, f) } },
        fan = z.fan?.let { a -> get<Int?> { o, f -> hvac.getHvacFanSpeed(a, { o(it) }, f) } },
        driverTemp = z.driverTemp?.let { a -> get<Float?> { o, f -> hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS, { o(it) }, f) } },
        rearTemp = z.rearTemp?.let { a -> get<Float?> { o, f -> hvac.getHvacTemperature(a, TemperatureUnit.CELSIUS, { o(it) }, f) } },
    ).also { Log.i(TAG, "saved $it") }

    private val ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig.writable: Boolean
        get() = accessType == AccessType.WRITE || accessType == AccessType.READ_WRITE

    /** 비동기 콜백 API를 1.5초 제한의 suspend 호출로 바꾼다. 실패·시간 초과는 null. */
    private suspend fun <T> get(call: (ok: (T) -> Unit, fail: (Exception) -> Unit) -> Unit): T? =
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<T?> { cont ->
                runCatching {
                    call({ if (cont.isActive) cont.resume(it) }, { e ->
                        Log.w(TAG, "hvac call failed", e)
                        if (cont.isActive) cont.resume(null)
                    })
                }.onFailure { e ->
                    Log.w(TAG, "hvac call threw", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

    private suspend fun set(call: (ok: () -> Unit, fail: (Exception) -> Unit) -> Unit): Boolean =
        get<Boolean> { o, f -> call({ o(true) }, f) } == true

    private companion object {
        const val TAG = "GoiHvac"
        const val TIMEOUT_MS = 1500L
    }
}
