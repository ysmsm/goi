package dev.seungmin.goi.ui

import dev.seungmin.goi.care.CareMode
import dev.seungmin.goi.data.EventType
import dev.seungmin.goi.data.TripEvent
import dev.seungmin.goi.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Test

class TripTitleTest {
    private fun trip(at: Long, mode: CareMode, brake: Boolean = false) =
        TripRecord(at, at + 60_000, listOf(mode), if (brake) listOf(TripEvent(EventType.HARD_BRAKE, at)) else emptyList())

    @Test
    fun `무사 운반 제목은 같은 모드의 무사 운반마다 차례로 돈다`() {
        val titles = CareMode.DRINK.safeTitles
        val history = listOf(
            trip(0, CareMode.DRINK),
            trip(100_000, CareMode.DRINK, brake = true), // 흔들린 운반은 세지 않는다
            trip(200_000, CareMode.FOOD), // 다른 모드도 세지 않는다
            trip(300_000, CareMode.DRINK),
            trip(400_000, CareMode.DRINK),
            trip(500_000, CareMode.DRINK),
        )
        assertEquals(titles[0], history[0].title(history))
        assertEquals(titles[1], history[3].title(history))
        assertEquals(titles[2], history[4].title(history))
        assertEquals(titles[0], history[5].title(history))
    }

    @Test
    fun `흔들림이 있었으면 도착 사실만 말한다`() {
        val t = trip(0, CareMode.DRINK, brake = true)
        assertEquals("음료를 옮겼어요", t.title(listOf(t)))
    }
}
