import java.util.Properties

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
        versionCode = 2
        versionName = "0.1.1"
    }

    aaptOptions {
        noCompress += "pkg"
    }

    // 签名参数放 android/signing.properties（已 gitignore），没有该文件时 release 不签名
    val signProps = Properties()
    val signPropsFile = rootProject.file("signing.properties")
    if (signPropsFile.exists()) signPropsFile.inputStream().use { signProps.load(it) }

    signingConfigs {
        if (signProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signProps.getProperty("storeFile"))
                storePassword = signProps.getProperty("storePassword")
                keyAlias = signProps.getProperty("keyAlias")
                keyPassword = signProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    lint {
        // targetSdk 28 是刻意为之（见 defaultConfig 注释），不是疏漏
        disable += "ExpiredTargetSdkVersion"
        abortOnError = false
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
