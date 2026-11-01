plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.seungmin.goi"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.seungmin.goi"
        // Pleos Connect는 서명 키 교체(lineage) 지원 때문에 API 28 이상을 요구한다
        minSdk = 28
        // Pleos Connect Emulator v2.1.0 = Android 14 (API 34)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // AAOS 표준 차량 속성(PERF_VEHICLE_SPEED)을 읽기 위한 플랫폼 라이브러리
    useLibrary("android.car")

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 공모전 제출용 release APK. 배포 키가 정해지면 별도 signingConfig로 바꾼다
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fused.location)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}
