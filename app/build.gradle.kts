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
        // 原生播放使用 Media3 ExoPlayer（要求 API 21+），因此最低系统版本上调到 Android 5.0。
        // 原生方案用系统硬件解码直连央视 CDN，是零卡顿的关键；WebView 网页方案保留为自动兜底。
        minSdk = 21
        targetSdk = 28
        versionCode = 7
        versionName = "4.0"
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

    lint {
        // release 构建默认执行 lintVital，需要从 dl.google.com 现下 lint-checks / intellij-core /
        // kotlin-compiler 等工具包（约数百 MB）；网络不稳时会让 CI 与本地 release 构建直接失败。
        // 本项目不使用 lint 门禁，关闭以保证打包稳定与提速。
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.multidex)
    // 腾讯 X5 (TBS) 内核 SDK（WebView 兜底路径用）
    implementation(libs.tencent.tbs)
    // 原生播放：Media3 ExoPlayer + HLS
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.hls)
    implementation(libs.androidx.media3.ui)
}
