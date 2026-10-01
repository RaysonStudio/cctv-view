import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localProperties.load(FileInputStream(localPropertiesFile))
}

android {
    namespace = "com.raysonstudio.cctv_view"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raysonstudio.cctv_view"
        // 面向老旧电视盒子：参考 CCTV_Viewer，最低支持 Android 4.2 (API 17)。
        // 注意：腾讯 X5/TBS 内核本身要求 Android 4.0 (API 14) 及以上，
        // AndroidX 依赖要求 API 14 及以上，因此无法降到 Android 2.x (API <= 10)。
        minSdk = 17
        targetSdk = 28
        versionCode = 5
        versionName = "3.0"
        multiDexEnabled = true
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    kotlinOptions {
        jvmTarget = "21"
    }

    signingConfigs {
        create("release") {

            val keystoreFile =
                System.getenv("KEYSTORE_FILE")
                    ?: localProperties.getProperty("KEYSTORE_FILE")

            if (keystoreFile != null) {

                storeFile = file(keystoreFile)

                storePassword =
                    System.getenv("KEYSTORE_PASSWORD")
                        ?: localProperties.getProperty("KEYSTORE_PASSWORD")

                keyAlias =
                    System.getenv("KEY_ALIAS")
                        ?: localProperties.getProperty("KEY_ALIAS")

                keyPassword =
                    System.getenv("KEY_PASSWORD")
                        ?: localProperties.getProperty("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig =
                signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.multidex)
    // 腾讯 X5 (TBS) 内核 SDK
    implementation(libs.tencent.tbs)
}
