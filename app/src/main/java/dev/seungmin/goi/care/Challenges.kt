package dev.seungmin.goi.care

import dev.seungmin.goi.data.EventType
import dev.seungmin.goi.data.TripRecord

/**
 * 모드별 챌린지(와이어프레임 F6). 점수가 아니라 "짐과 동승자를 지켰는가"를 센다.
 * 진행도는 운반 기록에서 매번 다시 계산하므로 따로 저장하지 않는다.
 */
enum class Challenge(
    val mode: CareMode,
    val title: String,
    /** 달성 조건 한 줄. */
    val rule: String,
    val target: Int,
    val unit: String,
    /** 이 운반이 진행도에 더하는 값. */
    private val gain: (TripRecord) -> Int,
) {
    DRINK_NO_SPILL(
        CareMode.DRINK, "커피 한 방울도 안 흘리기", "음료를 급출발·급제동 없이 5번 옮기기", 5, "번",
        { t -> if (t.count(EventType.HARD_BRAKE) + t.count(EventType.HARD_START) == 0) 1 else 0 },
    ),
    FRAGILE_SAFE(
        CareMode.FRAGILE, "케이크 무사 운반", "파손주의 물건을 흔들림 없이 1번 옮기기", 1, "번",
        { t -> if (t.safe) 1 else 0 },
    ),
    PET_CALM(
        CareMode.PET, "멀미 없는 드라이브", "반려동물과 급커브 없이 30분 달리기", 30, "분",
        { t -> if (t.count(EventType.SHARP_TURN) == 0) t.minutes else 0 },
    ),
    FOOD_CLEAN(
        CareMode.FOOD, "국물 한 방울도 지키기", "포장 음식을 급제동 없이 3번 옮기기", 3, "번",
        { t -> if (t.count(EventType.HARD_BRAKE) == 0) 1 else 0 },
    ),
    BABY_SLEEP(
        CareMode.BABY, "깨우지 않고 도착", "아기와 급출발·급제동 없이 5번 이동하기", 5, "번",
        { t -> if (t.count(EventType.HARD_BRAKE) + t.count(EventType.HARD_START) == 0) 1 else 0 },
    );

    fun progress(trips: List<TripRecord>): ChallengeProgress {
        var sum = 0
        var doneAt: Long? = null
        // 오래된 기록부터 더해 목표를 처음 넘긴 운반의 날짜를 달성일로 쓴다
        trips.filter { mode in it.modes }.sortedBy { it.endedAt }.forEach { t ->
            sum += gain(t)
            if (doneAt == null && sum >= target) doneAt = t.endedAt
        }
        return ChallengeProgress(this, sum.coerceAtMost(target), doneAt)
    }

    companion object {
        fun all(trips: List<TripRecord>) = CareMode.displayOrder.map { m -> entries.first { it.mode == m }.progress(trips) }

        /** 홈에 보여 줄 하나: 켜진 모드의 진행 중 챌린지, 없으면 가장 달성에 가까운 것. */
        fun featured(trips: List<TripRecord>, active: Set<CareMode>): ChallengeProgress? {
            val open = all(trips).filter { !it.done }
            return open.firstOrNull { it.challenge.mode in active } ?: open.maxByOrNull { it.ratio }
        }
    }
}

data class ChallengeProgress(val challenge: Challenge, val value: Int, val doneAt: Long?) {
    val done: Boolean get() = doneAt != null
    val ratio: Float get() = value.toFloat() / challenge.target
    val text: String get() = "$value/${challenge.target}${if (challenge.unit == "분") "분" else ""}"
}
