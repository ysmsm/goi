package dev.seungmin.goi.voice

import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.SpeechToText as PleosStt
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.tts.TextToSpeech as PleosTts
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode

/**
 * 주행 중 고이의 출력은 소리뿐이다: 짧은 안내 음성(TTS)과 즉시 피드백 효과음 1회.
 * 음성 명령은 Pleos SpeechToText로 받는다. 둘 다 온디바이스 모드라 앱 등록(Client ID)이 필요 없다.
 */
class Voice(private val context: Context) {
    private var tts: PleosTts? = null
    private var stt: PleosStt? = null
    private var ttsReady = false
    private var sttReady = false
    private var tone: ToneGenerator? = null
    private var onResult: ((String?) -> Unit)? = null
    private var lastPartial: String = ""

    /** 듣는 중 상태가 바뀔 때 호출한다. */
    var onListeningChanged: (Boolean) -> Unit = {}

    private val sttListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = onListeningChanged(true)
        override fun onUpdated(p0: String, p1: Boolean) {
            lastPartial = p0
            if (p1) finish(p0)
        }

        override fun onUpdatedEpdData(p0: Long, p1: Long) = Unit
        override fun onUpdatedRms(p0: Double) = Unit
        override fun onEndedRecognition() = finish(lastPartial.ifBlank { null })
        override fun onError(p0: SttError) {
            Log.w(TAG, "stt error $p0")
            finish(null)
        }
    }

    private val ttsListener = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() = Unit
        override fun onError(p0: TtsError) { Log.w(TAG, "tts error $p0") }
        override fun onStop() = Unit
        override fun onUpdatedRms(p0: Double) = Unit
    }

    fun start() {
        if (tts == null) {
            val t = PleosTts(context, TtsMode.ON_DEVICE)
            tts = t
            runCatching {
                t.initialize(object : ai.pleos.playground.tts.listener.OnInitializeListener {
                    override fun onSuccess() {
                        ttsReady = true
                        runCatching { t.setLocale(Locale.KOREA) }
                        runCatching { t.addEventListener(ttsListener) }
                    }

                    override fun onFailure(p0: TtsError) { Log.w(TAG, "tts init $p0") }
                })
            }.onFailure { Log.w(TAG, "tts init", it) }
        }
        if (stt == null) {
            val s = PleosStt(context, SttMode.ON_DEVICE)
            stt = s
            runCatching {
                s.initialize(object : ai.pleos.playground.stt.listener.OnInitializeListener {
                    override fun onSuccess() {
                        sttReady = true
                        runCatching { s.setLocale(Locale.KOREA) }
                        runCatching { s.addListener(sttListener) }
                    }

                    override fun onFailure(p0: SttError) { Log.w(TAG, "stt init $p0") }
                })
            }.onFailure { Log.w(TAG, "stt init", it) }
        }
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70) }.getOrNull()
    }

    fun stop() {
        tts?.let { t ->
            runCatching { t.removeEventListener(ttsListener) }
            runCatching { t.release() }
        }
        stt?.let { s ->
            runCatching { s.removeListener(sttListener) }
            runCatching { s.release() }
        }
        tts = null
        stt = null
        ttsReady = false
        sttReady = false
        tone?.release()
        tone = null
    }

    fun speak(text: String) {
        Log.i(TAG, "speak: $text")
        if (ttsReady) runCatching { tts?.speak(text, true) }.onFailure { Log.w(TAG, "speak", it) }
    }

    /** 즉시 피드백: 짧은 효과음 1회. */
    fun beep() {
        runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 180) }
    }

    /** 한 번 듣고 최종 문장(또는 실패 시 null)을 돌려준다. */
    fun listen(onText: (String?) -> Unit) {
        if (!sttReady) {
            onText(null)
            return
        }
        onResult = onText
        lastPartial = ""
        runCatching { stt?.request() }.onFailure {
            Log.w(TAG, "stt request", it)
            finish(null)
        }
        onListeningChanged(true)
    }

    fun cancelListening() {
        if (onResult == null) return
        runCatching { stt?.stop() }
        finish(null)
    }

    private fun finish(text: String?) {
        val cb = onResult ?: return
        onResult = null
        onListeningChanged(false)
        Log.i(TAG, "heard: $text")
        cb(text)
    }

    private companion object {
        const val TAG = "GoiVoice"
    }
}
