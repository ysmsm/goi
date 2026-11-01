package dev.seungmin.goi.care

import dev.seungmin.goi.data.EventType
import kotlin.math.abs
import kotlin.math.tan

/**
 * 차속·조향각으로 쏠림을 계산해 임계치를 넘으면 이벤트를 낸다.
 * - 횡가속도 a_y ≈ v²·tan(δ/i)/L (v: 차속, δ: 핸들 조향각, i: 조향 기어비, L: 축거)
 * - 앞뒤 가속도는 약 1초 전 차속과 비교한다. 센서 갱신 주기가 들쭉날쭉해도 순간값이 튀지 않게 한다.
 * 한 번의 급제동·급출발·급커브는 한 번만 센다: 판정 값이 기준의 [rearm] 배 아래로 내려가야 다시 센다.
 * 같은 종류는 [cooldownMs] 안에 반복하지 않는다.
 */
class MotionDetector(
    private val steeringRatio: Float = 14.5f,
    private val wheelbaseM: Float = 3.0f,
    private val cooldownMs: Long = 4_000,
    private val windowMs: Long = 1_000,
    private val rearm: Float = 0.6f,
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()
    private val lastFired = mutableMapOf<EventType, Long>()
    /** 기준을 넘은 채로 이어지는 중인 종류. 내려갈 때까지 다시 세지 않는다. */
    private val ongoing = mutableSetOf<EventType>()

    fun reset() {
        samples.clear()
        ongoing.clear()
    }

    fun onSample(speedKmh: Float?, steeringDeg: Float?, sensitivity: Sensitivity, now: Long): List<EventType> {
        val v = (speedKmh ?: return emptyList()) / 3.6f
        val out = mutableListOf<EventType>()

        samples.addLast(now to v)
        while (samples.size > 2 && now - samples[1].first >= windowMs) samples.removeFirst()
        val (t0, v0) = samples.first()
        val dt = (now - t0) / 1000f
        if (dt >= windowMs / 2000f && dt <= 3f) {
            val a = (v - v0) / dt
            check(EventType.HARD_BRAKE, -a, sensitivity.decel, now, out)
            check(EventType.HARD_START, if (v > 1f) a else 0f, sensitivity.accel, now, out)
        }

        if (steeringDeg != null && v > 3f) {
            val wheelRad = Math.toRadians((steeringDeg / steeringRatio).toDouble())
            val lateral = (v * v * tan(wheelRad) / wheelbaseM).toFloat()
            check(EventType.SHARP_TURN, abs(lateral), sensitivity.lateral, now, out)
        } else {
            ongoing -= EventType.SHARP_TURN
        }
        return out
    }

    private fun check(type: EventType, value: Float, threshold: Float, now: Long, out: MutableList<EventType>) {
        when {
            value >= threshold -> if (ongoing.add(type)) fire(type, now, out)
            value < threshold * rearm -> ongoing -= type
        }
    }

    private fun fire(type: EventType, now: Long, out: MutableList<EventType>) {
        if (lastFired[type]?.let { now - it < cooldownMs } == true) return
        lastFired[type] = now
        out += type
    }
}
