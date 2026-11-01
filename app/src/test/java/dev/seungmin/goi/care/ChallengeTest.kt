package dev.seungmin.goi.care

import dev.seungmin.goi.data.EventType
import dev.seungmin.goi.data.TripEvent
import dev.seungmin.goi.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChallengeTest {
    private fun trip(end: Long, mode: CareMode, minutes: Int = 10, vararg events: EventType) =
        TripRecord(end - minutes * 60_000L, end, listOf(mode), events.map { TripEvent(it, end) })

    @Test
    fun `음료는 급가감속 없는 운반만 센다`() {
        val trips = listOf(
            trip(1, CareMode.DRINK),
            trip(2, CareMode.DRINK, 10, EventType.HARD_BRAKE),
            trip(3, CareMode.DRINK, 10, EventType.CURVE_NOTICE, EventType.SHARP_TURN), // 커브는 음료 챌린지와 무관
            trip(4, CareMode.FOOD),
        )
        val p = Challenge.DRINK_NO_SPILL.progress(trips)
        assertEquals(2, p.value)
        assertNull(p.doneAt)
        assertEquals("2/5", p.text)
    }

    @Test
    fun `목표를 처음 넘긴 운반 날짜가 달성일이다`() {
        val trips = listOf(trip(200, CareMode.FRAGILE), trip(100, CareMode.FRAGILE, 10, EventType.HARD_BRAKE), trip(300, CareMode.FRAGILE))
        assertEquals(200L, Challenge.FRAGILE_SAFE.progress(trips).doneAt)
    }

    @Test
    fun `반려동물은 급커브 없는 주행 시간을 분으로 더한다`() {
        val trips = listOf(trip(1, CareMode.PET, 20), trip(2, CareMode.PET, 15, EventType.SHARP_TURN), trip(3, CareMode.PET, 25))
        val p = Challenge.PET_CALM.progress(trips)
        assertEquals(30, p.value) // 45분이지만 목표에서 멈춘다
        assertEquals(3L, p.doneAt)
        assertEquals("30/30분", p.text)
    }

    @Test
    fun `홈에는 켜진 모드의 진행 중 챌린지를 먼저 보여 준다`() {
        val trips = listOf(trip(1, CareMode.DRINK), trip(2, CareMode.DRINK))
        assertEquals(Challenge.BABY_SLEEP, Challenge.featured(trips, setOf(CareMode.BABY))?.challenge)
        assertEquals(Challenge.DRINK_NO_SPILL, Challenge.featured(trips, emptySet())?.challenge)
    }
}
