package dev.seungmin.goi.care

import kotlin.random.Random

/**
 * 말하기 예시. 모드마다 짐·동승자 이름과 그에 어울리는 동사만 짝지어 만든다("아기 샀어", "케이크 자요" 같은 조합을 막는다).
 * 예시는 사용자가 하려는 일을 알리는 말로 쓰고, 아기·반려동물에는 "싣다"를 쓰지 않는다. 만든 예시는 모두 [VoiceCommand]가 알아듣는다.
 */
object VoiceExample {
    private class Words(val nouns: List<String>, val verbs: List<String>)

    private val words = mapOf(
        CareMode.FRAGILE to Words(listOf("케이크", "화분", "와인"), listOf("옮길래", "실었어", "샀어")),
        CareMode.FOOD to Words(listOf("치킨", "피자", "족발"), listOf("샀어", "실었어", "포장했어")),
        CareMode.DRINK to Words(listOf("커피", "버블티"), listOf("샀어", "실었어")),
        CareMode.PET to Words(listOf("강아지", "고양이"), listOf("태웠어", "데리고 갈래")),
        CareMode.BABY to Words(listOf("아기"), listOf("태웠어", "데리고 갈래")),
    )

    const val SLEEP = "아기 자요"
    const val WAKE = "아기 깼어"

    /** [mode]를 켜는 예시 전부. [also]면 이미 켜진 모드에 더하는 말("치킨도 샀어")로 만든다. */
    fun all(mode: CareMode, also: Boolean = false): List<String> {
        val w = words.getValue(mode)
        return w.nouns.flatMap { n -> w.verbs.map { v -> if (also) "${n}도 $v" else "$n $v" } }
    }

    /**
     * 지금 켜진 모드([active])에서 말해 볼 만한 예시 후보. 켜진 모드가 있으면 아직 안 켠 모드를 더하는 말과 수면 모드 말을 고른다.
     * 아기 모드가 꺼져 있으면 더할 모드가 남아 있고 켜져 있으면 수면 모드 말이 있으므로 후보는 비지 않는다.
     */
    fun candidates(active: Set<CareMode>, sleeping: Boolean = false): List<String> = buildList {
        if (active.isEmpty()) {
            CareMode.entries.forEach { addAll(all(it)) }
        } else {
            CareMode.entries.filter { it !in active }.forEach { addAll(all(it, also = true)) }
            if (CareMode.BABY in active) add(if (sleeping) WAKE else SLEEP)
        }
    }

    fun pick(active: Set<CareMode>, sleeping: Boolean = false, random: Random = Random.Default): String =
        candidates(active, sleeping).random(random)
}
