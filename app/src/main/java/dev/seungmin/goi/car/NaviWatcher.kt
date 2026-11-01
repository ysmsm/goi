package dev.seungmin.goi.car

import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 내비 경로상 다음 급커브와 남은 거리(m). */
data class UpcomingCurve(val type: String, val distanceM: Double)

/** NaviHelper의 TBT(턴 바이 턴) 정보에서 앞쪽 급커브를 찾는다. 경로 안내 중일 때만 값이 온다. */
class NaviWatcher(private val context: Context) {
    private val _curve = MutableStateFlow<UpcomingCurve?>(null)
    val curve: StateFlow<UpcomingCurve?> = _curve.asStateFlow()

    private var navi: NaviHelper? = null
    private val listener = object : NaviHelperEventListener {
        override fun onTBTInfo(p0: List<TBTInfo>) {
            _curve.value = p0.firstOrNull()?.takeIf { it.type in SHARP }?.let { UpcomingCurve(it.type.name, it.distance) }
        }

        override fun onRouteEnded() {
            _curve.value = null
        }

        override fun onRouteCancelled() {
            _curve.value = null
        }
    }

    fun start() {
        if (navi != null) return
        val n = NaviHelper(context)
        navi = n
        n.initialize(
            {
                runCatching { n.addListener(listener) }.onFailure { Log.w(TAG, "addListener", it) }
                runCatching { n.getTBTInfo() }
            },
            { Log.w(TAG, "NaviHelper init failed", it) },
        )
    }

    fun stop() {
        navi?.let { n ->
            runCatching { n.removeListener(listener) }
            runCatching { n.release() }
        }
        navi = null
    }

    /** 데모·테스트용 주입. */
    fun inject(curve: UpcomingCurve?) {
        _curve.value = curve
    }

    private companion object {
        const val TAG = "GoiNavi"
        val SHARP = setOf(TurnType.TURN_SHARP_L, TurnType.TURN_SHARP_R, TurnType.TURN_UTURN)
    }
}
