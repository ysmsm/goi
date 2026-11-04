package dev.seungmin.goi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.seungmin.goi.car.Gear
import dev.seungmin.goi.car.UpcomingCurve
import dev.seungmin.goi.care.CareMode
import dev.seungmin.goi.data.Place

/**
 * 에뮬레이터에서 주행 시나리오를 재현한다. 예:
 * adb shell am broadcast -a dev.seungmin.goi.DEMO -p dev.seungmin.goi --es gear D --ef speed 48
 * extras: gear(P|R|N|D), speed(km/h), steer(도), brake(bool), trunk(bool), door(bool),
 *         curve("SHARP_R:250" | "none"), enter(장소 id | "any"), say(발화 문장), reset(bool),
 *         elapse(분: 주행 시간을 앞당겨 휴식 안내 확인),
 *         route("lat,lng,이름": 내비 경로 요청), via("lat,lng,이름;...": 경유지), routecancel(bool),
 *         navtrace(bool: 내비 위치·TBT를 GoiNavTrace 로그로), savehere(모드: 지금 GPS 위치를 픽업 장소로 저장),
 *         place("id,이름,lat,lng,반경m,모드": 픽업 장소 등록·변경)
 */
class DemoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = (context.applicationContext as GoiApplication).engine
        val vehicle = engine.vehicle
        if (intent.getBooleanExtra("reset", false)) {
            vehicle.clearOverrides()
            engine.navi.inject(null)
        }
        intent.getStringExtra("gear")?.let { g -> vehicle.override { it.copy(gear = Gear.valueOf(g)) } }
        if (intent.hasExtra("speed")) vehicle.override { it.copy(speedKmh = intent.getFloatExtra("speed", 0f)) }
        if (intent.hasExtra("steer")) vehicle.override { it.copy(steeringDeg = intent.getFloatExtra("steer", 0f)) }
        if (intent.hasExtra("brake")) vehicle.override { it.copy(brakePressed = intent.getBooleanExtra("brake", false)) }
        if (intent.hasExtra("trunk")) vehicle.override { it.copy(trunkOpen = intent.getBooleanExtra("trunk", false)) }
        if (intent.hasExtra("door")) vehicle.override { it.copy(driverDoorOpen = intent.getBooleanExtra("door", false)) }
        intent.getStringExtra("curve")?.let { c ->
            engine.navi.inject(
                if (c == "none") null else c.split(":").let { UpcomingCurve("TURN_" + it[0], it[1].toDouble()) },
            )
        }
        intent.getStringExtra("route")?.let { dest ->
            DebugNavi.requestRoute(context, dest, intent.getStringExtra("via")?.split(";")?.filter { it.isNotBlank() }.orEmpty())
        }
        if (intent.getBooleanExtra("routecancel", false)) DebugNavi.cancelRoute(context)
        if (intent.hasExtra("navtrace")) DebugNavi.trace(context, intent.getBooleanExtra("navtrace", false))
        intent.getStringExtra("savehere")?.let { engine.saveHere(CareMode.valueOf(it)) }
        intent.getStringExtra("place")?.split(",")?.let { p ->
            engine.simulatePlace(Place(p[0], p[1], p[2].toDouble(), p[3].toDouble(), p[4].toInt(), CareMode.valueOf(p[5]), true))
        }
        intent.getStringExtra("enter")?.let { engine.simulateEnter(it.takeIf { id -> id != "any" }) }
        intent.getStringExtra("say")?.let { engine.simulateUtterance(it) }
        if (intent.hasExtra("elapse")) engine.simulateElapsed(intent.getIntExtra("elapse", 0))
        Log.i("GoiDemo", "state ${engine.state.value.vehicle} active=${engine.state.value.active} forget=${engine.state.value.forget}")
    }
}
