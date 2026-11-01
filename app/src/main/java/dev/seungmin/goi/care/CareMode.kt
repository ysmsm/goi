package dev.seungmin.goi.care

/**
 * 고이의 모드 = 같은 센서 엔진에 끼우는 값 세트(민감도·커브 안내 거리·공조 항목·하차 알림 문구).
 * 선언 순서가 우선순위다(아기 > 반려동물 > 그 외). 모드가 겹치면 공조 값은 우선순위가 높은 모드를 따른다.
 */
enum class CareMode(
    /** 화면과 음성에서 부르는 이름. 기획서·와이어프레임의 모드 이름을 그대로 쓴다. */
    val label: String,
    /** 이름 아래 한 줄: 어떤 짐·동승자일 때 쓰는지. */
    val examples: String,
    /** 주행 화면 한 줄. */
    val drivingTitle: String,
    /** 운반 요약 제목(무사히 도착했을 때). 성취감을 주는 문구를 무사 운반마다 차례로 돌려 쓴다. */
    val safeTitles: List<String>,
    /** 운반 요약 제목(급제동 등이 있었을 때). 점수를 매기지 않고 도착 사실만 말한다. */
    val doneTitle: String,
    /** 하차 알림 문장. 짐이 트렁크에 있으면 [trunkAlert]를 쓴다. */
    val alert: String,
    val trunkAlert: String?,
    /** 내리기 전에 반드시 확인 버튼을 눌러야 닫히는 알림(사람·동물). */
    val mustConfirm: Boolean,
    val hvacItems: List<HvacItem>,
    val defaults: ModeSettings,
    /** 공조 제어는 주행 중(기어 P가 아닐 때)에만 한다. 주차 중 차에 두고 내리는 것을 돕지 않는다. */
    val hvacOnlyWhileDriving: Boolean,
) {
    BABY(
        label = "아기",
        examples = "뒷좌석 카시트",
        drivingTitle = "아기와 이동 중",
        safeTitles = listOf("아기가 편안하게 도착했어요! 👶", "흔들림 없는 부드러운 운전이었어요! 🍼", "완벽해요! 아기도 편안했을 거예요 😊"),
        doneTitle = "아기와 도착했어요",
        alert = "뒷좌석에 아기가 있어요",
        trunkAlert = null,
        mustConfirm = true,
        hvacItems = listOf(HvacItem.REAR_TEMP, HvacItem.NO_DIRECT_WIND, HvacItem.FRESH_CYCLE),
        defaults = ModeSettings(
            Sensitivity.HIGH, curveLeadM = 200,
            hvac = setOf(HvacItem.REAR_TEMP, HvacItem.NO_DIRECT_WIND, HvacItem.FRESH_CYCLE), rearTempC = 23f,
        ),
        hvacOnlyWhileDriving = true,
    ),
    PET(
        label = "반려동물",
        examples = "강아지·고양이",
        drivingTitle = "반려동물과 이동 중",
        safeTitles = listOf("편안한 드라이브였어요! 🐾", "흔들림 없이 함께 도착했어요! 🐶", "완벽해요! 멀미 걱정 없는 운전이었어요 ✨"),
        doneTitle = "반려동물과 도착했어요",
        alert = "뒷좌석에 반려동물이 있어요",
        trunkAlert = null,
        mustConfirm = true,
        hvacItems = listOf(HvacItem.REAR_TEMP, HvacItem.FRESH_CYCLE),
        defaults = ModeSettings(
            Sensitivity.HIGH, curveLeadM = 200,
            hvac = setOf(HvacItem.REAR_TEMP, HvacItem.FRESH_CYCLE), rearTempC = 22f, restEveryMin = 60,
        ),
        hvacOnlyWhileDriving = true,
    ),
    FRAGILE(
        label = "파손주의",
        examples = "케이크·화분·와인",
        drivingTitle = "파손주의 물건 운반 중",
        safeTitles = listOf("모양 그대로 도착했어요! 🎂", "흔들림 하나 없었어요! 👏", "완벽해요! 조심조심 성공이에요 ✨"),
        doneTitle = "파손주의 물건을 옮겼어요",
        alert = "파손주의 물건을 챙기세요",
        trunkAlert = "트렁크에 파손주의 물건이 있어요",
        mustConfirm = false,
        // 케이크: 실내 온도 낮춤, 화분: 바람 줄임. 기획서대로 운전자가 켰을 때만 적용한다
        hvacItems = listOf(HvacItem.COOLER, HvacItem.SOFT_FAN),
        defaults = ModeSettings(Sensitivity.MAX, curveLeadM = 300, hvac = emptySet(), rearTempC = 21f),
        hvacOnlyWhileDriving = false,
    ),
    FOOD(
        label = "포장 음식",
        examples = "치킨·피자·족발",
        drivingTitle = "포장 음식 운반 중",
        safeTitles = listOf("국물 한 방울 안 샜어요! 🍲", "포장 그대로 도착했어요! 🍗", "완벽해요! 깔끔하게 옮겼어요 👍"),
        doneTitle = "포장 음식을 옮겼어요",
        alert = "포장 음식을 챙기세요",
        trunkAlert = "트렁크에 포장 음식이 있어요",
        mustConfirm = false,
        hvacItems = listOf(HvacItem.ODOR_OUT),
        defaults = ModeSettings(Sensitivity.NORMAL, curveLeadM = 200, hvac = setOf(HvacItem.ODOR_OUT), rearTempC = 22f),
        hvacOnlyWhileDriving = false,
    ),
    DRINK(
        label = "음료",
        examples = "커피·버블티·국물",
        drivingTitle = "음료 운반 중",
        safeTitles = listOf("하나도 안 흘렸어요! ☕", "찰랑임 없이 도착했어요! 🧋", "완벽해요! 쏟을 틈이 없었어요 🎉"),
        doneTitle = "음료를 옮겼어요",
        alert = "컵홀더의 음료를 챙기세요",
        trunkAlert = null,
        mustConfirm = false,
        hvacItems = emptyList(),
        defaults = ModeSettings(Sensitivity.HIGH, curveLeadM = 200, hvac = emptySet(), rearTempC = 22f),
        hvacOnlyWhileDriving = false,
    );

    val usesRearTemp: Boolean get() = HvacItem.REAR_TEMP in hvacItems
    val usesFreshCycle: Boolean get() = HvacItem.FRESH_CYCLE in hvacItems
    val usesRest: Boolean get() = this == PET

    companion object {
        /** 화면에 놓는 순서(자주 쓰는 순). 우선순위(선언 순서)와 다르다. */
        val displayOrder = listOf(FRAGILE, FOOD, DRINK, PET, BABY)
    }
}

/** 모드가 켤 수 있는 공조 항목. 하나하나가 설정 화면의 스위치 한 줄이다. */
enum class HvacItem(
    val label: String,
    val description: String,
    /** 상태 요약에 쓰는 짧은 말. */
    val short: String,
    val freshAir: Boolean = false,
    val fanDelta: Int? = null,
    val tempDelta: Float? = null,
) {
    ODOR_OUT(
        "냄새 빼기", "외기 유입으로 바꾸고 바람을 2단계 세게 해 음식 냄새가 실내에 배지 않게 합니다.", "외기 유입·바람 세게",
        freshAir = true, fanDelta = 2,
    ),
    COOLER("실내 온도 낮추기", "케이크나 초콜릿이 녹지 않도록 실내 온도를 2°C 낮춥니다.", "온도 2°C 낮춤", tempDelta = -2f),
    SOFT_FAN("바람 줄이기", "화분이나 꽃이 마르지 않도록 바람 세기를 1단계 줄입니다.", "바람 약하게", fanDelta = -1),
    REAR_TEMP("뒷좌석 온도 유지", "주행하는 동안 뒷좌석을 정한 온도로 맞춥니다.", "뒷좌석 온도 유지"),
    NO_DIRECT_WIND("직접 바람 피하기", "아기에게 바람이 세게 닿지 않도록 바람 세기를 2단계 줄입니다.", "바람 약하게", fanDelta = -2),
    FRESH_CYCLE("주기적 환기", "내기 순환이 오래 이어지지 않도록 정한 간격마다 1분 동안 외기를 들입니다.", "주기적 환기"),
}

enum class Sensitivity(
    val label: String,
    /** 선택한 단계 아래에 보여 주는 설명. */
    val description: String,
    /** 급제동 판정 감속도(m/s²) */
    val decel: Float,
    /** 급출발 판정 가속도(m/s²) */
    val accel: Float,
    /** 급커브 판정 횡가속도(m/s²) */
    val lateral: Float,
) {
    NORMAL("보통", "확실히 급한 제동·출발·커브만 알립니다.", decel = 3.4f, accel = 3.0f, lateral = 3.0f),
    HIGH("높음", "조금 급한 제동·출발·커브도 알립니다.", decel = 2.6f, accel = 2.3f, lateral = 2.3f),
    MAX("최고", "작은 흔들림도 모두 알립니다.", decel = 2.0f, accel = 1.8f, lateral = 1.7f),
}

data class ModeSettings(
    val sensitivity: Sensitivity,
    /** 커브 사전 안내를 커브 몇 m 전에 할지. 0이면 끈다. */
    val curveLeadM: Int,
    /** 켠 공조 항목. 모드가 지원하지 않는 항목은 무시한다. */
    val hvac: Set<HvacItem>,
    val rearTempC: Float,
    /** 급제동·급출발·급커브 때 효과음. 꺼도 기록은 남긴다. */
    val feedbackSound: Boolean = true,
    /** 도착(기어 P + 운전석 문 열림) 때 하차 알림. */
    val arrivalAlert: Boolean = true,
    /** 주기적 환기 간격(분). */
    val freshEveryMin: Int = 20,
    /** 휴식 안내 간격(분). 0이면 끈다. 반려동물 모드만 쓴다. */
    val restEveryMin: Int = 0,
) {
    companion object {
        /** 커브 안내 거리 선택지(m). 미리 알아채고 천천히 줄일 수 있게 넉넉히 잡는다. */
        val CURVE_LEAD_OPTIONS = listOf(0, 100, 200, 300)
        val FRESH_OPTIONS = listOf(10, 20, 30)
        val REST_OPTIONS = listOf(0, 60, 120)
        const val TEMP_MIN = 16f
        const val TEMP_MAX = 30f
    }
}

/** 켜진 모드들을 합친 실제 동작 값. */
data class MergedCare(
    val sensitivity: Sensitivity,
    /** 가장 민감한 임계치를 정한 모드. */
    val sensitivityFrom: CareMode,
    val curveLeadM: Int,
    val feedbackSound: Boolean,
    val freshAir: Boolean?,
    val fanDelta: Int?,
    val tempDelta: Float?,
    val rearTempC: Float?,
    val periodicFreshMin: Int?,
    val restEveryMin: Int?,
    /** 지금 적용 중인 공조 항목(모드 순서대로, 중복 없음). */
    val hvacItems: List<HvacItem>,
    /** 켜 두었지만 주차 중이라 쉬고 있는 공조 항목. */
    val hvacWaiting: List<HvacItem>,
) {
    val hasHvac: Boolean
        get() = freshAir != null || fanDelta != null || tempDelta != null || rearTempC != null || periodicFreshMin != null

    companion object {
        /**
         * 가장 민감한 임계치 + 공조는 우선순위가 높은 모드의 값을 먼저 쓴다. 외기 유입은 한 모드라도 원하면 켠다.
         * [parked]는 기어 P다. 신호 대기처럼 D에서 멈춘 것은 주행으로 본다(공조를 껐다 켰다 하지 않게).
         */
        fun of(active: Set<CareMode>, settings: Map<CareMode, ModeSettings>, parked: Boolean): MergedCare? {
            if (active.isEmpty()) return null
            val ordered = CareMode.entries.filter { it in active }
            fun set(m: CareMode) = settings[m] ?: m.defaults
            fun enabled(m: CareMode) = m.hvacItems.filter { it in set(m).hvac }
            val running = ordered.filter { !it.hvacOnlyWhileDriving || !parked }
            val items = running.flatMap { m -> enabled(m).map { m to it } }
            fun <T> first(pick: (CareMode, HvacItem) -> T?): T? = items.firstNotNullOfOrNull { (m, i) -> pick(m, i) }
            // 같은 민감도면 우선순위가 높은(앞의) 모드를 기준으로 표시한다
            val top = ordered.maxBy { set(it).sensitivity.ordinal }
            return MergedCare(
                sensitivity = set(top).sensitivity,
                sensitivityFrom = top,
                curveLeadM = ordered.maxOf { set(it).curveLeadM },
                feedbackSound = ordered.any { set(it).feedbackSound },
                freshAir = if (items.any { it.second.freshAir }) true else null,
                fanDelta = first { _, i -> i.fanDelta },
                tempDelta = first { _, i -> i.tempDelta },
                rearTempC = first { m, i -> if (i == HvacItem.REAR_TEMP) set(m).rearTempC else null },
                periodicFreshMin = first { m, i -> if (i == HvacItem.FRESH_CYCLE) set(m).freshEveryMin else null },
                restEveryMin = ordered.filter { it.usesRest }.map { set(it).restEveryMin }.filter { it > 0 }.minOrNull(),
                hvacItems = items.map { it.second }.distinct(),
                hvacWaiting = (ordered - running.toSet()).flatMap { enabled(it) }.distinct(),
            )
        }
    }
}
