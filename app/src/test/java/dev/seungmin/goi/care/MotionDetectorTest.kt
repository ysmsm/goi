package dev.seungmin.goi.care

import dev.seungmin.goi.data.EventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionDetectorTest {
    /** 10Hz 샘플로 [from]→[to] km/h를 [seconds] 동안 일정하게 바꾼다. */
    private fun ramp(d: MotionDetector, from: Float, to: Float, seconds: Float, s: Sensitivity, start: Long = 0, steer: Float = 0f): List<EventType> {
        val steps = (seconds * 10).toInt()
        return (0..steps).flatMap { i -> d.onSample(from + (to - from) * i / steps, steer, s, start + i * 100L) }
    }

    @Test
    fun `부드러운 가감속은 이벤트가 없다`() {
        val d = MotionDetector()
        assertEquals(emptyList<EventType>(), ramp(d, 0f, 50f, 10f, Sensitivity.MAX)) // 1.4 m/s²
    }

    @Test
    fun `급제동은 민감도에 따라 판정이 달라진다`() {
        // 50→20km/h를 1.5초 = 약 5.6 m/s²: 모든 민감도에서 급제동
        assertTrue(EventType.HARD_BRAKE in ramp(MotionDetector(), 50f, 20f, 1.5f, Sensitivity.NORMAL))
        // 50→35km/h를 2초 = 약 2.1 m/s²: 최고에서만 급제동
        assertTrue(EventType.HARD_BRAKE in ramp(MotionDetector(), 50f, 35f, 2f, Sensitivity.MAX))
        assertTrue(EventType.HARD_BRAKE !in ramp(MotionDetector(), 50f, 35f, 2f, Sensitivity.NORMAL))
    }

    @Test
    fun `같은 종류는 쿨다운 안에 한 번만`() {
        val d = MotionDetector()
        val events = ramp(d, 60f, 0f, 3f, Sensitivity.NORMAL)
        assertEquals(1, events.count { it == EventType.HARD_BRAKE })
    }

    @Test
    fun `빠른 속도에서 핸들을 크게 꺾으면 급커브`() {
        // 40km/h, 핸들 90도: a_y = 11.1² · tan(6.2°) / 3 ≈ 4.5 m/s²
        assertTrue(EventType.SHARP_TURN in ramp(MotionDetector(), 40f, 40f, 1f, Sensitivity.NORMAL, steer = 90f))
        // 10km/h, 핸들 90도: 약 0.28 m/s²
        assertTrue(EventType.SHARP_TURN !in ramp(MotionDetector(), 10f, 10f, 1f, Sensitivity.MAX, steer = 90f))
    }

    @Test
    fun `길게 이어지는 급가속·급제동은 한 번만 센다`() {
        // 0→40km/h를 6초(약 1.85 m/s²): 최고 민감도 기준을 넘는 채로 쿨다운(4초)보다 오래 이어진다
        val d = MotionDetector()
        assertEquals(1, ramp(d, 0f, 40f, 6f, Sensitivity.MAX).count { it == EventType.HARD_START })
        // 100→0km/h를 7초(약 4 m/s²)
        assertEquals(1, ramp(MotionDetector(), 100f, 0f, 7f, Sensitivity.NORMAL).count { it == EventType.HARD_BRAKE })
    }

    @Test
    fun `가라앉았다가 다시 급제동하면 다시 센다`() {
        val d = MotionDetector()
        val first = ramp(d, 60f, 30f, 1.5f, Sensitivity.NORMAL)
        val cruise = ramp(d, 30f, 30f, 5f, Sensitivity.NORMAL, start = 1_500)
        val second = ramp(d, 30f, 0f, 1.5f, Sensitivity.NORMAL, start = 6_600)
        assertEquals(2, (first + cruise + second).count { it == EventType.HARD_BRAKE })
    }
}
