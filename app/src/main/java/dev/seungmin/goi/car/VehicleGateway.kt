package dev.seungmin.goi.car

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Gear(val label: String) { P("P"), R("R"), N("N"), D("D"), UNKNOWN("-") }

/** 고이가 보는 차량 상태. null은 "이 차/권한에서 받을 수 없음"이다. */
data class VehicleSnapshot(
    val speedKmh: Float? = null,
    val gear: Gear = Gear.UNKNOWN,
    val brakePressed: Boolean? = null,
    val steeringDeg: Float? = null,
    val trunkOpen: Boolean? = null,
    val driverDoorOpen: Boolean? = null,
    val updatedAt: Long = 0L,
) {
    /** 차속이 있으면 차속으로, 없으면 기어로 주행 여부를 판단한다. */
    val driving: Boolean
        get() = when (val s = speedKmh) {
            null -> gear == Gear.D || gear == Gear.R
            else -> s > 1f
        }

    /** 기어 P면 주차. 기어를 못 읽으면 차속으로 판단한다. 신호 대기(D, 0km/h)는 주차가 아니다. */
    val parked: Boolean
        get() = if (gear != Gear.UNKNOWN) gear == Gear.P else !driving
}

/**
 * Pleos Vehicle SDK(기어·브레이크·조향각·도어)와 AAOS 표준 속성(차속)을 하나의 상태로 모은다.
 * 디버그 빌드의 데모 주입값([overrides])이 있으면 그 값을 우선한다.
 */
class VehicleGateway(private val context: Context) {
    private val sensors = MutableStateFlow(VehicleSnapshot())
    private val overrides = MutableStateFlow(VehicleSnapshot())
    private val _snapshot = MutableStateFlow(VehicleSnapshot())
    val snapshot: StateFlow<VehicleSnapshot> = _snapshot.asStateFlow()

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var carProperties: CarPropertyManager? = null
    private val unregisters = mutableListOf<() -> Unit>()

    val sdk: Vehicle? get() = vehicle

    fun start() {
        if (vehicle != null) return
        val v = Vehicle(context)
        runCatching { v.initialize() }.onFailure { Log.w(TAG, "Vehicle.initialize", it) }
        vehicle = v
        listenGear(v)
        listenBrake(v)
        listenSteering(v)
        listenDoor(v, DoorValue.REAR) { open -> sensors.update { it.copy(trunkOpen = open) } }
        listenDoor(v, DoorValue.ROW_1_LEFT) { open -> sensors.update { it.copy(driverDoorOpen = open) } }
        listenSpeed()
    }

    fun stop() {
        unregisters.forEach { runCatching(it) }
        unregisters.clear()
        runCatching { car?.disconnect() }
        car = null
        carProperties = null
        runCatching { vehicle?.release() }
        vehicle = null
    }

    /** 데모·테스트용 값 주입. null 필드는 실제 센서 값을 쓴다. */
    fun override(transform: (VehicleSnapshot) -> VehicleSnapshot) {
        overrides.update(transform)
        publish()
    }

    fun clearOverrides() {
        overrides.value = VehicleSnapshot()
        publish()
    }

    private fun publish() {
        val s = sensors.value
        val o = overrides.value
        _snapshot.value = VehicleSnapshot(
            speedKmh = o.speedKmh ?: s.speedKmh,
            gear = if (o.gear != Gear.UNKNOWN) o.gear else s.gear,
            brakePressed = o.brakePressed ?: s.brakePressed,
            steeringDeg = o.steeringDeg ?: s.steeringDeg,
            trunkOpen = o.trunkOpen ?: s.trunkOpen,
            driverDoorOpen = o.driverDoorOpen ?: s.driverDoorOpen,
            updatedAt = System.currentTimeMillis(),
        )
    }

    private fun sensor(transform: (VehicleSnapshot) -> VehicleSnapshot) {
        sensors.update(transform)
        publish()
    }

    private fun listenGear(v: Vehicle) {
        val gear = v.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(p0: VehicleGear?) = sensor { it.copy(gear = p0.toGear()) }
            override fun onFailed(p0: Exception) { Log.w(TAG, "gear listener", p0) }
        }
        gear.getCurrentGearSelectionState({ g -> sensor { it.copy(gear = g.toGear()) } }, { Log.w(TAG, "gear get", it) })
        guard("gear") { gear.registerGearSelection(listener) }
        unregisters += { gear.unregisterGearSelection(listener) }
    }

    private fun listenBrake(v: Vehicle) {
        val brake = v.getBrake()
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(p0: Boolean?) = sensor { it.copy(brakePressed = p0) }
            override fun onFailed(p0: Exception) { Log.w(TAG, "brake listener", p0) }
        }
        guard("brake") { brake.registerBrakePedalStatus(listener) }
        unregisters += { brake.unregisterBrakePedalStatus(listener) }
    }

    private fun listenSteering(v: Vehicle) {
        val wheel = v.getSteeringWheel()
        val listener = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(p0: Float?) = sensor { it.copy(steeringDeg = p0) }
            override fun onFailed(p0: Exception) { Log.w(TAG, "steering listener", p0) }
        }
        guard("steering") { wheel.registerSteeringWheelAngle(listener) }
        unregisters += { wheel.unregisterSteeringWheelAngle(listener) }
    }

    private fun listenDoor(v: Vehicle, door: DoorValue, onChange: (Boolean?) -> Unit) {
        val doors = v.getDoor()
        val area = DoorArea(door)
        val listener = object : DoorPositionListener {
            override fun onPositionUpdated(p0: Int?) = onChange(p0?.let { it > 0 })
            override fun onFailed(p0: Exception) { Log.w(TAG, "door $door listener", p0) }
        }
        doors.getCurrentDoorPosition(area, { pos -> onChange(pos?.let { it > 0 }) }, { Log.w(TAG, "door $door get", it) })
        guard("door $door") { doors.registerDoorPosition(area, listener) }
        unregisters += { doors.unregisterDoorPosition(area, listener) }
    }

    /** 차속은 Vehicle SDK에 없어서 AAOS 표준 속성 PERF_VEHICLE_SPEED(m/s)를 읽는다. CAR_SPEED는 런타임 권한이다. */
    private fun listenSpeed() {
        val c = runCatching { Car.createCar(context) }.getOrNull() ?: return
        car = c
        val manager = runCatching { c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager }.getOrNull() ?: return
        carProperties = manager
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val mps = (value.value as? Float) ?: return
                sensor { it.copy(speedKmh = mps * 3.6f) }
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) { Log.w(TAG, "speed error $propertyId") }
        }
        val ok = guard("speed") {
            manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_FAST)
        }
        if (ok) unregisters += { manager.unregisterCallback(callback) }
    }

    private fun guard(what: String, block: () -> Unit): Boolean =
        runCatching(block).onFailure { Log.w(TAG, "register $what failed", it) }.isSuccess

    private companion object {
        const val TAG = "GoiVehicle"
    }
}

private fun VehicleGear?.toGear(): Gear = when (this) {
    VehicleGear.GEAR_PARK -> Gear.P
    VehicleGear.GEAR_REVERSE -> Gear.R
    VehicleGear.GEAR_NEUTRAL -> Gear.N
    null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
    else -> Gear.D
}
