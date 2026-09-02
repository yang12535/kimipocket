plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kimbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.kimbox"
        minSdk = 26
        // targetSdk 压到 28：规避高版本 Android 对应用私有目录 exec 的限制
        // （与 Termux 同款策略；本应用只走侧载，不受 Play 的 targetSdk 要求约束）
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    aaptOptions {
        noCompress += "pkg"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("org.apache.commons:commons-compress:1.27.1")
}
