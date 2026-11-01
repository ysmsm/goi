package dev.seungmin.goi

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.seungmin.goi.ui.GoiRoot

class MainActivity : ComponentActivity() {
    private val engine get() = (application as GoiApplication).engine

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // 권한이 새로 허용되면 차량 리스너를 다시 등록한다(차속·위치는 런타임 권한)
        if (it.values.any { granted -> granted }) {
            engine.stop()
            engine.start()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestRuntimePermissions()
        startForegroundService(Intent(this, CareService::class.java))
        engine.start()
        setContent {
            val state by engine.state.collectAsStateWithLifecycle()
            GoiRoot(state, engine)
        }
    }

    override fun onStart() {
        super.onStart()
        engine.visible = true
    }

    override fun onStop() {
        engine.visible = false
        super.onStop()
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add("android.car.permission.CAR_SPEED")
            add("pleos.car.permission.FUSED_LOCATION")
            add(android.Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(android.Manifest.permission.POST_NOTIFICATIONS)
        }.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionRequest.launch(wanted.toTypedArray())
    }
}
