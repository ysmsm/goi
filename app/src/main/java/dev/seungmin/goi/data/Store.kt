package dev.seungmin.goi.data

import android.content.Context
import dev.seungmin.goi.care.CareMode
import dev.seungmin.goi.care.HvacItem
import dev.seungmin.goi.care.ModeSettings
import dev.seungmin.goi.care.Sensitivity
import org.json.JSONArray
import org.json.JSONObject

/** 픽업 장소. 위치는 차량 안에만 저장하고 서버로 보내지 않는다. */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    val mode: CareMode,
    val autoSuggest: Boolean,
)

enum class EventType(val label: String, /** 주행 중 처음 감지됐을 때 하는 말. */ val spoken: String) {
    HARD_BRAKE("급제동", "급제동이에요"),
    HARD_START("급출발", "급출발이에요"),
    SHARP_TURN("급커브", "급커브예요"),
    CURVE_NOTICE("커브 안내", ""),
}

data class TripEvent(val type: EventType, val atMillis: Long)

data class TripRecord(
    val startedAt: Long,
    val endedAt: Long,
    val modes: List<CareMode>,
    val events: List<TripEvent>,
    /** 차속을 적분한 주행 거리(m). 차속을 못 읽으면 0. */
    val distanceM: Double = 0.0,
    /** 픽업 장소 제안으로 모드를 켰다면 그 장소 이름. */
    val fromPlace: String? = null,
) {
    fun count(type: EventType) = events.count { it.type == type }

    val minutes: Int get() = ((endedAt - startedAt) / 60_000L).toInt()

    /** 무사 운반 = 급제동·급출발·급커브가 한 번도 없음. 커브 안내는 감점이 아니다. */
    val safe: Boolean get() = events.none { it.type != EventType.CURVE_NOTICE }
}

/** 설정·장소·기록을 SharedPreferences(JSON)에 둔다. 운전 기록은 기기 밖으로 내보내지 않는다. */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("goi", Context.MODE_PRIVATE)

    fun loadSettings(): Map<CareMode, ModeSettings> = CareMode.entries.associateWith { mode ->
        val raw = prefs.getString("settings.${mode.name}", null) ?: return@associateWith mode.defaults
        runCatching {
            val o = JSONObject(raw)
            ModeSettings(
                sensitivity = Sensitivity.valueOf(o.getString("sensitivity")),
                // 선택지에 없는 값(이전 버전의 초 단위 curveLeadSec 등)은 끄기만 유지하고 기본 거리로 옮긴다
                curveLeadM = when {
                    o.optInt("curveLeadM", -1) in ModeSettings.CURVE_LEAD_OPTIONS -> o.getInt("curveLeadM")
                    o.optInt("curveLeadSec", -1) == 0 -> 0
                    else -> mode.defaults.curveLeadM
                },
                // 이전 버전의 "공조 자동" 한 개 스위치는 항목별 스위치로 옮긴다
                hvac = when {
                    o.has("hvac") -> o.getJSONArray("hvac").let { a ->
                        (0 until a.length()).mapNotNull { runCatching { HvacItem.valueOf(a.getString(it)) }.getOrNull() }.toSet()
                    }
                    o.optBoolean("hvacAuto", true) -> mode.defaults.hvac
                    else -> emptySet()
                },
                rearTempC = o.getDouble("rearTempC").toFloat(),
                feedbackSound = o.optBoolean("feedbackSound", true),
                arrivalAlert = o.optBoolean("arrivalAlert", true),
                freshEveryMin = o.optInt("freshEveryMin", mode.defaults.freshEveryMin).takeIf { it in ModeSettings.FRESH_OPTIONS }
                    ?: mode.defaults.freshEveryMin,
                restEveryMin = o.optInt("restEveryMin", mode.defaults.restEveryMin).takeIf { it in ModeSettings.REST_OPTIONS }
                    ?: mode.defaults.restEveryMin,
            )
        }.getOrDefault(mode.defaults)
    }

    fun saveSettings(mode: CareMode, s: ModeSettings) {
        val o = JSONObject()
            .put("sensitivity", s.sensitivity.name)
            .put("curveLeadM", s.curveLeadM)
            .put("hvac", JSONArray(s.hvac.map { it.name }))
            .put("rearTempC", s.rearTempC.toDouble())
            .put("feedbackSound", s.feedbackSound)
            .put("arrivalAlert", s.arrivalAlert)
            .put("freshEveryMin", s.freshEveryMin)
            .put("restEveryMin", s.restEveryMin)
        prefs.edit().putString("settings.${mode.name}", o.toString()).apply()
    }

    fun loadActive(): Set<CareMode> =
        prefs.getStringSet("active", emptySet())!!.mapNotNull { runCatching { CareMode.valueOf(it) }.getOrNull() }.toSet()

    fun saveActive(active: Set<CareMode>) {
        prefs.edit().putStringSet("active", active.map { it.name }.toSet()).apply()
    }

    fun loadPlaces(): List<Place> {
        val raw = prefs.getString("places", null) ?: return seedPlaces().also(::savePlaces)
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Place(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    lat = o.getDouble("lat"),
                    lng = o.getDouble("lng"),
                    radiusM = o.getInt("radiusM"),
                    mode = CareMode.valueOf(o.getString("mode")),
                    autoSuggest = o.getBoolean("autoSuggest"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun savePlaces(places: List<Place>) {
        val arr = JSONArray()
        places.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id).put("name", p.name)
                    .put("lat", p.lat).put("lng", p.lng)
                    .put("radiusM", p.radiusM).put("mode", p.mode.name)
                    .put("autoSuggest", p.autoSuggest),
            )
        }
        prefs.edit().putString("places", arr.toString()).apply()
    }

    fun loadTrips(): List<TripRecord> {
        val raw = prefs.getString("trips", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val modes = o.getJSONArray("modes")
                val events = o.getJSONArray("events")
                TripRecord(
                    startedAt = o.getLong("startedAt"),
                    endedAt = o.getLong("endedAt"),
                    modes = (0 until modes.length()).map { CareMode.valueOf(modes.getString(it)) },
                    events = (0 until events.length()).map {
                        val e = events.getJSONObject(it)
                        TripEvent(EventType.valueOf(e.getString("type")), e.getLong("at"))
                    },
                    distanceM = o.optDouble("distanceM", 0.0),
                    fromPlace = o.optString("fromPlace").ifEmpty { null },
                )
            }
        }.getOrDefault(emptyList())
    }

    fun saveTrips(trips: List<TripRecord>) {
        val arr = JSONArray()
        trips.take(MAX_TRIPS).forEach { t ->
            arr.put(
                JSONObject()
                    .put("startedAt", t.startedAt).put("endedAt", t.endedAt)
                    .put("modes", JSONArray(t.modes.map { it.name }))
                    .put("events", JSONArray(t.events.map { JSONObject().put("type", it.type.name).put("at", it.atMillis) }))
                    .put("distanceM", t.distanceM)
                    .put("fromPlace", t.fromPlace ?: ""),
            )
        }
        prefs.edit().putString("trips", arr.toString()).apply()
    }

    /** 1단계 O2O 목업: 제휴 매장을 픽업 장소로 미리 넣어 둔다(가정 시나리오, 실제 제휴 아님). */
    private fun seedPlaces() = listOf(
        Place("seed-bakery", "달콤베이커리", 37.5045, 127.0490, 50, CareMode.FRAGILE, true),
        Place("seed-chicken", "바삭치킨 역삼점", 37.5006, 127.0364, 50, CareMode.FOOD, true),
        Place("seed-cafe", "카페 드라이브스루", 37.4979, 127.0276, 80, CareMode.DRINK, true),
    )

    private companion object {
        const val MAX_TRIPS = 30
    }
}
