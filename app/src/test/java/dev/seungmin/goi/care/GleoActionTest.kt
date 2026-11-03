package dev.seungmin.goi.care

import dev.seungmin.goi.car.Gear
import dev.seungmin.goi.car.VehicleSnapshot
import dev.seungmin.goi.data.EventType
import dev.seungmin.goi.data.TripEvent
import dev.seungmin.goi.data.TripRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GleoActionTest {
    private fun state(vararg modes: CareMode, sleeping: Boolean = false) = CareUiState(active = modes.toSet(), sleeping = sleeping)

    @Test
    fun `Gleo 함수와 파라미터를 명령으로 바꾼다`() {
        assertEquals(
            GleoCall.Command(GleoCall.SET_CARE_MODE, VoiceCommand.Turn(CareMode.FOOD, true)),
            GleoCall.parse("SET_CARE_MODE", mapOf("mode" to "food", "enabled" to true)),
        )
        // boolean이 문자열로 와도 받는다
        assertEquals(
            GleoCall.Command(GleoCall.SET_CARE_MODE, VoiceCommand.Turn(CareMode.FRAGILE, false)),
            GleoCall.parse("SET_CARE_MODE", mapOf("mode" to "FRAGILE", "enabled" to "false")),
        )
        assertEquals(
            GleoCall.Command(GleoCall.SET_CARE_MODE, VoiceCommand.Turn(null, false)),
            GleoCall.parse("SET_CARE_MODE", mapOf("mode" to "all", "enabled" to false)),
        )
        assertEquals(
            GleoCall.Command(GleoCall.SET_BABY_SLEEP, VoiceCommand.Sleep(true)),
            GleoCall.parse("SET_BABY_SLEEP", mapOf("sleeping" to true)),
        )
        assertEquals(GleoCall.Status, GleoCall.parse("GET_CARE_STATUS", emptyMap()))
        assertNull(GleoCall.parse("PLAY_MUSIC", emptyMap()))
    }

    @Test
    fun `파라미터가 빠지거나 알 수 없으면 다시 묻는다`() {
        assertEquals(GleoCall.Missing(GleoCall.SET_CARE_MODE, "mode"), GleoCall.parse("SET_CARE_MODE", mapOf("enabled" to true)))
        assertEquals(GleoCall.Missing(GleoCall.SET_CARE_MODE, "mode"), GleoCall.parse("SET_CARE_MODE", mapOf("mode" to "cake", "enabled" to true)))
        assertEquals(GleoCall.Missing(GleoCall.SET_CARE_MODE, "enabled"), GleoCall.parse("SET_CARE_MODE", mapOf("mode" to "pet")))
        // "모두 켜기"는 없다
        assertEquals(GleoCall.Missing(GleoCall.SET_CARE_MODE, "mode"), GleoCall.parse("SET_CARE_MODE", mapOf("mode" to "all", "enabled" to true)))
        assertEquals(GleoCall.Missing(GleoCall.SET_BABY_SLEEP, "sleeping"), GleoCall.parse("SET_BABY_SLEEP", emptyMap()))

        val reply = GleoReply.of(GleoCall.Missing(GleoCall.SET_CARE_MODE, "enabled"), state(), state(), null)
        assertEquals(GleoReply.Status.ASKING, reply.status)
        assertEquals("enabled", reply.data.single()["missingField"])
    }

    @Test
    fun `모드를 더하면 겹침 결과를 Callback에 담는다`() {
        val call = GleoCall.Command(GleoCall.SET_CARE_MODE, VoiceCommand.Turn(CareMode.FOOD, true))
        val reply = GleoReply.of(call, state(CareMode.FRAGILE), state(CareMode.FRAGILE, CareMode.FOOD), null)
        assertEquals(GleoReply.Status.SUCCESS, reply.status)
        assertTrue(reply.message.startsWith("The 포장 음식 mode was added"))
        val data = reply.data.single()
        assertEquals("파손주의, 포장 음식", data["activeModes"])
        assertEquals("최고", data["sensitivity"])
        assertEquals("외기 유입·바람 세게", data["climate"])
        assertEquals(GleoReply.Mode.VOICE, reply.mode)
    }

    @Test
    fun `이미 켜진 모드와 수면 중 응답`() {
        val on = GleoCall.Command(GleoCall.SET_CARE_MODE, VoiceCommand.Turn(CareMode.PET, true))
        assertEquals("The 반려동물 mode is already on.", GleoReply.of(on, state(CareMode.PET), state(CareMode.PET), null).message)

        val sleep = GleoCall.Command(GleoCall.SET_BABY_SLEEP, VoiceCommand.Sleep(true))
        val reply = GleoReply.of(sleep, state(CareMode.BABY), state(CareMode.BABY, sleeping = true), null)
        assertEquals(GleoReply.Mode.BEEP, reply.mode)
        assertEquals(true, reply.data.single()["babySleeping"])
    }

    @Test
    fun `상태 질문에는 지금까지의 운반 기록과 뒷좌석 공조로 답한다`() {
        val trip = TripRecord(
            startedAt = 0L, endedAt = 12 * 60_000L, modes = listOf(CareMode.BABY),
            events = listOf(TripEvent(EventType.HARD_BRAKE, 1L), TripEvent(EventType.CURVE_NOTICE, 2L)),
        )
        val s = state(CareMode.BABY).copy(vehicle = VehicleSnapshot(speedKmh = 40f, gear = Gear.D))
        val data = GleoReply.of(GleoCall.Status, s, s, trip).data.single()
        assertEquals(23, data["rearSeatTargetCelsius"])
        assertTrue((data["climate"] as String).contains("바람 약하게"))
        assertEquals(1, data["hardBrakes"])
        assertEquals(1, data["curveNotices"])
        assertEquals(12, data["tripMinutes"])
        assertFalse(data.containsKey("climateWaitingUntilDriving"))

        assertEquals("No care mode is on.", GleoReply.of(GleoCall.Status, state(), state(), null).message)
    }
}
