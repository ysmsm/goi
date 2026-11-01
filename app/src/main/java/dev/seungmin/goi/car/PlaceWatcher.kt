package dev.seungmin.goi.car

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.util.Log
import dev.seungmin.goi.data.Place

/**
 * FusedLocation SDK의 근접 알림으로 등록한 픽업 장소 진입을 감지한다.
 * 위치 판정은 차량 안에서만 하고, 경로·위치를 저장하거나 서버로 보내지 않는다.
 */
class PlaceWatcher(private val context: Context, private val onEnter: (placeId: String) -> Unit) {
    private var fused: FusedLocation? = null
    private val alertToPlace = mutableMapOf<String, String>()
    private var watched: List<Place> = emptyList()

    @Volatile
    var lastLocation: LocationData? = null
        private set

    private val locationListener = object : FusedLocationListener {
        override fun onChanged(p0: LocationData?) {
            if (p0 != null) lastLocation = p0
        }

        override fun onError(p0: Exception) { Log.w(TAG, "location", p0) }
    }

    fun start() {
        if (fused != null) return
        val f = FusedLocation(context)
        runCatching { f.initialize() }.onFailure { Log.w(TAG, "initialize", it) }
        fused = f
        runCatching {
            f.getLastKnownLocation(locationListener)
            f.registerFusedLocationCallback(locationListener)
        }.onFailure { Log.w(TAG, "register location", it) }
        watch(watched)
    }

    fun stop() {
        val f = fused ?: return
        alertToPlace.keys.forEach { runCatching { f.removeProximityAlert(it) } }
        alertToPlace.clear()
        runCatching { f.unregisterFusedLocationCallback(locationListener) }
        runCatching { f.release() }
        fused = null
    }

    /** 자동 제안이 켜진 장소만 근접 알림으로 등록한다. 목록이 바뀌면 전부 다시 등록한다. */
    fun watch(places: List<Place>) {
        watched = places
        val f = fused ?: return
        alertToPlace.keys.forEach { runCatching { f.removeProximityAlert(it) } }
        alertToPlace.clear()
        places.filter { it.autoSuggest }.forEach { place ->
            val request = ProximityAlertRequest(place.lat, place.lng, place.radiusM.toFloat(), setOf(ProximityTransition.ENTER))
            runCatching {
                f.addProximityAlert(request, object : ProximityAlertListener {
                    override fun onRegistered(p0: String) {
                        alertToPlace[p0] = place.id
                    }

                    override fun onTransition(p0: ProximityAlertEvent) {
                        if (p0.transition == ProximityTransition.ENTER) alertToPlace[p0.alertId]?.let(onEnter)
                    }

                    override fun onError(p0: Exception) { Log.w(TAG, "proximity ${place.name}", p0) }
                })
            }.onFailure { Log.w(TAG, "addProximityAlert ${place.name}", it) }
        }
    }

    private companion object {
        const val TAG = "GoiPlace"
    }
}
