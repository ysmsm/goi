package dev.seungmin.goi

import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DrivingInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 에뮬레이터 모의주행용(디버그 빌드 전용). 좌표로 내비 경로를 요청하고, 경로 안내 중
 * 내비가 보는 현재 위치·TBT·남은 거리를 `GoiNavTrace` 로그로 1초마다 남긴다.
 * 모의주행 브리지(scripts/nav-bridge.py)가 이 로그로 차속·조향·GPS를 맞춘다.
 *
 *   loc <lat> <lng> <heading>     내비 현재 위치(모의주행 중이면 시뮬레이션 위치)
 *   tbt <TYPE>:<m> ...            다음 안내 지점들
 *   drive <남은 m> <남은 초>       목적지까지
 *   arrived|waypoint|route|ended  경로 이벤트
 */
@SuppressLint("MissingPermission") // 디버그 매니페스트에 NAVI_ROUTE_SEARCH가 있다
object DebugNavi {
    private const val TAG = "GoiNavTrace"
    private val main = Handler(Looper.getMainLooper())
    private var navi: NaviHelper? = null
    private var connected = false
    private val pending = mutableListOf<(NaviHelper) -> Unit>()
    private var tracing = false

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            Log.i(TAG, "loc ${currentLocationInfo.latitude} ${currentLocationInfo.longitude} ${currentLocationInfo.heading}")
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            Log.i(TAG, "tbt " + tbtInfoList.joinToString(" ") { "${it.type.name}:${it.distance.toInt()}" })
        }

        override fun onDrivingInfo(drivingInfo: DrivingInfo) {
            Log.i(TAG, "drive ${drivingInfo.destination.distance} ${drivingInfo.destination.duration}")
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
            Log.i(TAG, "route ${routeStartInfo.distance} ${routeStartInfo.duration} ${routeStartInfo.name}")
        }

        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
            Log.i(TAG, "arrived ${destinationArrivedInfo.name}")
        }

        override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
            Log.i(TAG, "waypoint ${waypointArrivedInfo.name}")
        }

        override fun onRouteEnded() {
            Log.i(TAG, "ended")
        }

        override fun onRouteCancelled() {
            Log.i(TAG, "ended cancelled")
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            Log.i(TAG, "limit $speedLimit")
        }
    }

    private fun with(context: Context, block: (NaviHelper) -> Unit) {
        main.post {
            val n = navi
            if (n != null && connected) return@post block(n)
            pending += block
            if (n != null) return@post
            val created = NaviHelper(context.applicationContext)
            navi = created
            created.initialize(
                {
                    main.post {
                        connected = true
                        runCatching { created.addListener(listener) }
                        pending.toList().also { pending.clear() }.forEach { it(created) }
                    }
                },
                { Log.w(TAG, "NaviHelper init failed", it) },
            )
        }
    }

    /** "lat,lng,이름" 목적지와 같은 형식의 경유지들로 경로를 요청한다. */
    fun requestRoute(context: Context, destination: String, via: List<String>) = with(context) { n ->
        val dest = parse(destination)
        val info = RouteInfo(dest.first, dest.second, poiName = dest.third, poiId = "", address = dest.third, poiSubId = "")
        runCatching {
            if (via.isEmpty()) {
                n.requestRoute(info)
            } else {
                val points = via.take(WaypointIndex.values().size).mapIndexed { i, s ->
                    val p = parse(s)
                    RequestWaypointInfo(p.first, p.second, p.third, WaypointIndex.values()[i], poiId = "", address = p.third, poiSubId = "")
                }
                n.requestRouteWithWaypoints(RouteWithWaypointsRequest(info, points))
            }
        }.onFailure { Log.w(TAG, "requestRoute", it) }
        Log.i(TAG, "requested $destination via $via")
    }

    fun cancelRoute(context: Context) = with(context) { n -> runCatching { n.cancelRoute() } }

    /** 1초마다 현재 위치를 묻는다. TBT·남은 거리는 경로 안내 중 내비가 알아서 보낸다. */
    fun trace(context: Context, on: Boolean) = with(context) { n ->
        if (on == tracing) return@with
        tracing = on
        if (on) poll(n)
        Log.i(TAG, "trace $on")
    }

    private fun poll(n: NaviHelper) {
        if (!tracing) return
        runCatching { n.getCurrentLocationInfo() }
        main.postDelayed({ poll(n) }, 1_000)
    }

    private fun parse(s: String): Triple<Double, Double, String> {
        val p = s.split(",")
        return Triple(p[0].toDouble(), p[1].toDouble(), p.getOrElse(2) { "목적지" })
    }
}
