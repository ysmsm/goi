package dev.seungmin.goi.care

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAndMergeTest {
    private val defaults = CareMode.entries.associateWith { it.defaults }

    @Test
    fun `짧은 발화를 명령으로 바꾼다`() {
        assertEquals(VoiceCommand.Yes, VoiceCommand.parse("응"))
        assertEquals(VoiceCommand.No, VoiceCommand.parse("아니 괜찮아"))
        assertEquals(VoiceCommand.Turn(CareMode.FOOD, true), VoiceCommand.parse("치킨 모드 추가"))
        assertEquals(VoiceCommand.Turn(CareMode.FRAGILE, false), VoiceCommand.parse("케이크 모드 꺼"))
        assertEquals(VoiceCommand.Turn(CareMode.FRAGILE, true), VoiceCommand.parse("케이크 실었어"))
        assertEquals(VoiceCommand.Turn(CareMode.FRAGILE, true), VoiceCommand.parse("케이크 옮길래"))
        assertEquals(VoiceCommand.Turn(CareMode.FRAGILE, true), VoiceCommand.parse("케이크 옮겨줘"))
        assertEquals(VoiceCommand.Sleep(true), VoiceCommand.parse("아기 자요"))
        assertEquals(VoiceCommand.Sleep(false), VoiceCommand.parse("아기 깼어"))
        assertEquals(VoiceCommand.Turn(null, false), VoiceCommand.parse("모두 꺼"))
        assertEquals(VoiceCommand.Unknown, VoiceCommand.parse(null))
    }

    @Test
    fun `모드가 겹치면 가장 민감한 임계치와 우선순위 공조를 쓴다`() {
        val m = MergedCare.of(setOf(CareMode.FRAGILE, CareMode.FOOD), defaults, parked = false)!!
        assertEquals(Sensitivity.MAX, m.sensitivity)
        assertEquals(CareMode.FRAGILE, m.sensitivityFrom)
        assertEquals(300, m.curveLeadM)
        assertEquals(true, m.freshAir) // 음식 모드의 냄새 빼기
        assertEquals(2, m.fanDelta) // 파손주의 공조 항목은 기본 꺼짐이라 음식 값을 쓴다
        assertEquals(listOf(HvacItem.ODOR_OUT), m.hvacItems)

        val withBaby = MergedCare.of(setOf(CareMode.BABY, CareMode.FOOD), defaults, parked = false)!!
        assertEquals(-2, withBaby.fanDelta) // 아기 > 그 외
        assertEquals(true, withBaby.freshAir) // 외기 유입은 한 모드라도 원하면 켠다
        assertEquals(23f, withBaby.rearTempC)
        assertEquals(20, withBaby.periodicFreshMin)
    }

    @Test
    fun `공조 항목은 켠 것만 적용한다`() {
        val settings = defaults + (CareMode.FRAGILE to CareMode.FRAGILE.defaults.copy(hvac = setOf(HvacItem.COOLER)))
        val m = MergedCare.of(setOf(CareMode.FRAGILE), settings, parked = true)!!
        assertEquals(-2f, m.tempDelta)
        assertNull(m.fanDelta) // 바람 약하게는 꺼 둠
        val off = MergedCare.of(setOf(CareMode.FOOD), defaults + (CareMode.FOOD to CareMode.FOOD.defaults.copy(hvac = emptySet())), parked = false)!!
        assertEquals(false, off.hasHvac)
    }

    @Test
    fun `아기·반려동물 공조는 주차(P) 중에는 쉬고 신호 대기에서는 유지한다`() {
        val parked = MergedCare.of(setOf(CareMode.BABY), defaults, parked = true)!!
        assertNull(parked.rearTempC)
        assertEquals(false, parked.hasHvac)
        assertTrue(HvacItem.REAR_TEMP in parked.hvacWaiting)
        // 신호 대기(D, 0km/h)는 주차가 아니므로 공조를 껐다 켜지 않는다
        val atLight = MergedCare.of(setOf(CareMode.BABY), defaults, parked = false)!!
        assertEquals(23f, atLight.rearTempC)
    }

    @Test
    fun `휴식 안내와 효과음은 켜진 모드 설정을 따른다`() {
        val pet = MergedCare.of(setOf(CareMode.PET), defaults, parked = false)!!
        assertEquals(60, pet.restEveryMin)
        assertNull(MergedCare.of(setOf(CareMode.DRINK), defaults, parked = false)!!.restEveryMin)
        val quiet = defaults + (CareMode.DRINK to CareMode.DRINK.defaults.copy(feedbackSound = false))
        assertEquals(false, MergedCare.of(setOf(CareMode.DRINK), quiet, parked = false)!!.feedbackSound)
        assertEquals(true, MergedCare.of(setOf(CareMode.DRINK, CareMode.FOOD), quiet, parked = false)!!.feedbackSound)
    }

    @Test
    fun `하차 알림은 트렁크에 실은 모드만 트렁크 문구를 쓴다`() {
        val a = ForgetAlert(listOf(CareMode.FRAGILE, CareMode.FOOD), inTrunk = setOf(CareMode.FRAGILE))
        assertEquals("트렁크에 파손주의 물건이 있어요", a.headline)
        assertEquals(listOf("포장 음식을 챙기세요"), a.others)
        assertEquals(false, a.mustConfirm)
        assertEquals(true, ForgetAlert(listOf(CareMode.BABY, CareMode.DRINK)).mustConfirm)
    }
}
