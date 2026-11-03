package dev.seungmin.goi.care

import dev.seungmin.goi.data.EventType
import dev.seungmin.goi.data.TripRecord

/**
 * Gleo AI 음성 대화(Gleo Interface)로 부르는 함수. 정의는 `assets/schema.json`에 있고, 함수 이름이 곧 Intent action이다.
 * 자유로운 말("케이크 실었어", "치킨도 실었어")은 Gleo가 함수와 파라미터로 바꿔 주고, 앱은 정해진 값만 받는다.
 */
sealed interface GleoCall {
    val function: String

    data class Command(override val function: String, val command: VoiceCommand) : GleoCall

    data object Status : GleoCall {
        override val function = GET_CARE_STATUS
    }

    /** 필수 파라미터가 없거나 알 수 없는 값이다. Gleo가 사용자에게 다시 묻는다. */
    data class Missing(override val function: String, val field: String) : GleoCall

    companion object {
        const val SET_CARE_MODE = "SET_CARE_MODE"
        const val SET_BABY_SLEEP = "SET_BABY_SLEEP"
        const val GET_CARE_STATUS = "GET_CARE_STATUS"

        /** schema.json의 mode enum. 모드 값은 [CareMode] 이름을 소문자로 쓰고, "all"은 끌 때만 쓴다. */
        private const val ALL = "all"

        /** [params]는 ACTION_PARAM JSON을 푼 값이다. boolean이 문자열로 와도 받는다. */
        fun parse(action: String?, params: Map<String, Any?>): GleoCall? = when (action) {
            SET_CARE_MODE -> {
                val name = params["mode"]?.toString()?.trim()?.lowercase()
                val mode = CareMode.entries.firstOrNull { it.name.lowercase() == name }
                val on = bool(params["enabled"])
                when {
                    mode == null && name != ALL -> Missing(action, "mode")
                    on == null -> Missing(action, "enabled")
                    mode == null -> if (on) Missing(action, "mode") else Command(action, VoiceCommand.Turn(null, false))
                    else -> Command(action, VoiceCommand.Turn(mode, on))
                }
            }
            SET_BABY_SLEEP -> bool(params["sleeping"])?.let { Command(action, VoiceCommand.Sleep(it)) } ?: Missing(action, "sleeping")
            GET_CARE_STATUS -> Status
            else -> null
        }

        private fun bool(v: Any?): Boolean? = when (v) {
            is Boolean -> v
            else -> v?.toString()?.trim()?.lowercase()?.toBooleanStrictOrNull()
        }
    }
}

/**
 * Gleo AI에 돌려주는 Callback. [message]는 가이드대로 영어 한 문장이고(모드 이름 같은 항목은 한국어 허용),
 * Gleo는 [message]와 [data]로 사용자에게 할 말을 만든다.
 */
data class GleoReply(
    val functionName: String,
    val status: Status,
    val message: String,
    val data: List<Map<String, Any>> = emptyList(),
    val mode: Mode = Mode.VOICE,
) {
    enum class Status { SUCCESS, ASKING, FAIL }

    /** VOICE: Gleo가 말한다. BEEP: 완료음만 낸다(수면 모드). */
    enum class Mode { VOICE, BEEP, SILENT }

    companion object {
        /** [before]는 실행 전, [after]는 실행 후 상태다. [trip]은 진행 중인 운반의 지금까지 기록. */
        fun of(call: GleoCall, before: CareUiState, after: CareUiState, trip: TripRecord?): GleoReply = when (call) {
            is GleoCall.Missing -> GleoReply(
                call.function, Status.ASKING, askFor(call.field), listOf(mapOf("missingField" to call.field)),
            )
            GleoCall.Status -> GleoReply(
                call.function, Status.SUCCESS,
                if (after.active.isEmpty()) "No care mode is on." else "Here is the current care status.",
                listOf(status(after, trip)),
            )
            is GleoCall.Command -> GleoReply(
                call.function, Status.SUCCESS, commandMessage(call.command, before, after), listOf(status(after, trip)),
                // 아기가 자는 동안에는 Gleo도 말 대신 완료음만 낸다
                mode = if (after.sleeping) Mode.BEEP else Mode.VOICE,
            )
        }

        private fun askFor(field: String) = when (field) {
            "mode" -> "Which care mode to change is required."
            "enabled" -> "Whether to turn the mode on or off is required."
            else -> "Whether the baby is asleep is required."
        }

        private fun commandMessage(cmd: VoiceCommand, before: CareUiState, after: CareUiState): String = when (cmd) {
            is VoiceCommand.Turn -> when {
                cmd.mode == null ->
                    if (before.active.isEmpty()) "No care mode was on." else "All care modes are off and the climate settings are restored."
                cmd.on && cmd.mode in before.active -> "The ${cmd.mode.label} mode is already on."
                cmd.on && before.active.isNotEmpty() ->
                    "The ${cmd.mode.label} mode was added, and driving feedback follows the most sensitive active mode."
                cmd.on -> "The ${cmd.mode.label} mode is on."
                cmd.mode !in before.active -> "The ${cmd.mode.label} mode was already off."
                after.active.isEmpty() -> "The ${cmd.mode.label} mode is off and the climate settings are restored."
                else -> "The ${cmd.mode.label} mode is off."
            }
            is VoiceCommand.Sleep -> when {
                cmd.on == before.sleeping -> if (cmd.on) "Baby sleep mode is already on." else "Baby sleep mode was already off."
                cmd.on -> "Baby sleep mode is on, so feedback sounds are muted and voice guidance is shortened."
                else -> "Baby sleep mode is off."
            }
            else -> "The request was handled."
        }

        /** 상태 질문("지금까지 어때?", "아기 괜찮아?")에 답할 재료. 값이 없는 항목은 넣지 않는다. */
        private fun status(s: CareUiState, trip: TripRecord?): Map<String, Any> = buildMap {
            val m = s.merged
            put("activeModes", s.activeModes.joinToString(", ") { it.label }.ifEmpty { "없음" })
            if (m != null) {
                put("sensitivity", m.sensitivity.label)
                if (m.curveLeadM > 0) put("curveNoticeMeters", m.curveLeadM)
                put("climate", m.hvacItems.joinToString(", ") { it.short }.ifEmpty { "변경 없음" })
                m.rearTempC?.let { put("rearSeatTargetCelsius", it.toInt()) }
                if (m.hvacWaiting.isNotEmpty()) put("climateWaitingUntilDriving", m.hvacWaiting.joinToString(", ") { it.short })
            }
            if (CareMode.BABY in s.active) put("babySleeping", s.sleeping)
            if (trip != null) {
                put("tripMinutes", trip.minutes)
                put("hardBrakes", trip.count(EventType.HARD_BRAKE))
                put("hardStarts", trip.count(EventType.HARD_START))
                put("sharpTurns", trip.count(EventType.SHARP_TURN))
                put("curveNotices", trip.count(EventType.CURVE_NOTICE))
            }
        }
    }
}
