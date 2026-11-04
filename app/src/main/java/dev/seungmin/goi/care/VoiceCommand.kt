package dev.seungmin.goi.care

/** "응", "치킨 모드 추가", "아기 자요" 같은 짧은 한국어 발화를 명령으로 바꾼다. */
sealed interface VoiceCommand {
    data object Yes : VoiceCommand
    data object No : VoiceCommand
    data class Turn(val mode: CareMode?, val on: Boolean) : VoiceCommand
    data class Sleep(val on: Boolean) : VoiceCommand
    data object Unknown : VoiceCommand

    companion object {
        private val keywords = listOf(
            CareMode.BABY to listOf("아기", "아이", "애기", "유아"),
            CareMode.PET to listOf("강아지", "고양이", "반려", "멍멍", "댕댕", "펫"),
            CareMode.FRAGILE to listOf("케이크", "케익", "화분", "꽃", "와인", "파손", "깨지"),
            CareMode.FOOD to listOf("치킨", "피자", "족발", "음식", "포장", "배달", "떡볶이"),
            CareMode.DRINK to listOf("커피", "음료", "버블티", "라떼", "국물", "주스"),
        )

        fun parse(raw: String?): VoiceCommand {
            val t = raw?.replace(" ", "")?.trim().orEmpty()
            if (t.isEmpty()) return Unknown
            val off = listOf("꺼", "끄", "그만", "해제", "중지", "내렸", "내려", "뺐", "먹었").any { it in t }
            if (listOf("자요", "잠들", "재워", "자고있", "잔다", "자").any { t.endsWith(it) || "${it}요" in t } &&
                listOf("아기", "아이", "애기").any { it in t }
            ) return Sleep(on = !off)
            if (listOf("깼", "일어났", "수면끝").any { it in t }) return Sleep(on = false)
            val mode = keywords.firstOrNull { (_, words) -> words.any { it in t } }?.first
            if (mode != null) return Turn(mode, on = !off)
            if (off && listOf("모드", "모두", "전부", "다").any { it in t }) return Turn(null, on = false)
            if (listOf("아니", "괜찮", "됐어", "싫어", "노").any { t.startsWith(it) }) return No
            if (listOf("응", "네", "예", "어", "켜", "좋아", "그래", "웅", "오케이").any { t.startsWith(it) }) return Yes
            return Unknown
        }
    }
}
