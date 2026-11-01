package dev.seungmin.goi.ui

import dev.seungmin.goi.care.CareMode
import dev.seungmin.goi.care.CareUiState
import dev.seungmin.goi.care.HvacItem
import dev.seungmin.goi.care.MergedCare
import dev.seungmin.goi.data.TripRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 화면 문구 조립. 켜진 모드가 하는 일을 엔진과 같은 값([MergedCare])으로 말한다. */

fun formatTemp(t: Float) = if (t % 1f == 0f) t.toInt().toString() else String.format(Locale.US, "%.1f", t)

fun curveText(leadM: Int) = if (leadM > 0) "커브 ${leadM}m 전 안내" else "커브 안내 끔"

/** 주행 화면 보조 한 줄. 1~2초 안에 읽히게 두 가지만. */
fun MergedCare.drivingLine() = "민감도 ${sensitivity.label} · ${curveText(curveLeadM)}"

fun MergedCare.hvacText(): String = when {
    hvacItems.isNotEmpty() -> hvacItems.joinToString(" · ") { item ->
        if (item == HvacItem.REAR_TEMP && rearTempC != null) "뒷좌석 ${formatTemp(rearTempC)}°C" else item.short
    }
    hvacWaiting.isNotEmpty() -> "주행을 시작하면 적용"
    else -> "바꾸지 않음"
}

/** 켜진 모드의 실제 동작을 표 한 줄씩. Grid 겹침 화면(G4)과 Full 홈(F1)에서 쓴다. */
fun CareUiState.statusRows(): List<Pair<String, String>> {
    val m = merged ?: return emptyList()
    val many = active.size > 1
    return buildList {
        add("민감도" to (m.sensitivity.label + if (many) " · ${m.sensitivityFrom.label} 기준" else ""))
        add("커브 안내" to if (m.curveLeadM > 0) "${m.curveLeadM}m 전" else "끔")
        add("공조" to m.hvacText())
        m.restEveryMin?.let { add("휴식 안내" to "${it / 60}시간마다") }
        val alerts = activeModes.filter { settingsOf(it).arrivalAlert }
        add("도착 알림" to if (alerts.isEmpty()) "끔" else alerts.joinToString(" · ") { it.label })
        if (sleeping) add("수면 모드" to "켜짐 · 효과음 끔")
    }
}

/** 여러 모드면 이름을 잇고, 하나면 주행 문구를 쓴다. */
fun drivingTitle(modes: List<CareMode>) = if (modes.size == 1) modes.first().drivingTitle else modes.joinToString(" · ") { it.label }

private val dayFormat = SimpleDateFormat("M월 d일", Locale.KOREA)
private val dateTimeFormat = SimpleDateFormat("M/d HH:mm", Locale.KOREA)

fun dayText(millis: Long): String = dayFormat.format(Date(millis))
fun dateTimeText(millis: Long): String = dateTimeFormat.format(Date(millis))

/** "달콤베이커리에서 · 23분 · 8.4km" */
fun TripRecord.detail(): String = buildList {
    fromPlace?.let { add("${it}에서") }
    add(if (minutes < 1) "1분 미만" else "${minutes}분")
    if (distanceM >= 100) add(String.format(Locale.US, "%.1fkm", distanceM / 1000))
}.joinToString(" · ")

/** 무사 운반이면 같은 모드의 앞선 무사 운반 횟수로 축하 문구를 차례로 고른다([history]에 이 기록이 있어도 된다). */
fun TripRecord.title(history: List<TripRecord>): String {
    val top = CareMode.entries.firstOrNull { it in modes } ?: return "도착했어요"
    if (!safe) return top.doneTitle
    val before = history.count { it.safe && it.endedAt < endedAt && CareMode.entries.firstOrNull { m -> m in it.modes } == top }
    return top.safeTitles[before % top.safeTitles.size]
}

fun agoText(millis: Long, now: Long = System.currentTimeMillis()): String {
    val min = ((now - millis) / 60_000L).toInt()
    return if (min < 1) "방금" else "${min}분 전"
}
