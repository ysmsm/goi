package dev.seungmin.goi.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import dev.seungmin.goi.GoiApplication
import dev.seungmin.goi.care.GleoCall
import dev.seungmin.goi.care.GleoReply
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Gleo AI가 `assets/schema.json`의 함수를 부르면 받아서 엔진에 넘기고, 결과를 Callback 브로드캐스트로 돌려준다.
 * 함수는 모두 component "service"(sendBroadcast)라 주행 중에도 화면을 띄우지 않는다.
 * Gleo는 7초가 지나면 호출을 취소하므로 기다리지 않고 바로 답한다.
 */
class GleoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val params = params(intent)
        val call = GleoCall.parse(intent.action, params) ?: return
        val engine = (context.applicationContext as GoiApplication).engine
        // 앱 창을 연 적 없이 Gleo 호출로 프로세스가 떴어도 차량 리스너를 켠다
        engine.start()
        val reply = engine.onGleo(call)
        Log.i(TAG, "${intent.action} $params -> ${reply.status} ${reply.message}")
        respond(context, reply)
    }

    /** ACTION_PARAM(JSON 문자열)을 푼다. 문서 예시처럼 따옴표로 한 번 더 감싸 오거나, 파라미터가 extra로 따로 와도 받는다. */
    private fun params(intent: Intent): Map<String, Any?> {
        val extras = intent.extras ?: return emptyMap()
        val json = extras.getString(ACTION_PARAM)?.trim()?.let(::json)
        if (json != null) return json.keys().asSequence().associateWith { json.opt(it) }
        @Suppress("DEPRECATION")
        return extras.keySet().filter { it != ACTION_PARAM }.associateWith { extras.get(it) }
    }

    private fun json(raw: String): JSONObject? =
        runCatching { JSONObject(raw.removeSurrounding("\"")) }.getOrNull()
            ?: runCatching { JSONObject(JSONTokener(raw).nextValue() as String) }.getOrNull()

    private fun respond(context: Context, reply: GleoReply) {
        runCatching {
            val response = JSONObject()
                .put("functionName", reply.functionName)
                .put("status", reply.status.name.lowercase())
                .put("message", reply.message)
                .put("mode", reply.mode.name.lowercase())
            if (reply.data.isNotEmpty()) response.put("data", JSONArray(reply.data.map { JSONObject(it) }).toString())
            context.sendBroadcast(
                Intent(RESPONSE_ACTION)
                    .putExtra("packageName", context.packageName)
                    .putExtra("response", response.toString()),
            )
            Log.i(TAG, "callback $response")
        }.onFailure { Log.w(TAG, "callback failed", it) }
    }

    private companion object {
        const val TAG = "GoiGleo"
        const val ACTION_PARAM = "ACTION_PARAM"
        const val RESPONSE_ACTION = "ai.pleos.playground.intent.action.APP_ACTION_RESPONSE"
    }
}
