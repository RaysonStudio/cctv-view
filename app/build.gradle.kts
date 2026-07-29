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

val serverIp = localProperties.getProperty("server.ip", "11.45.1.4")
val serverPort = localProperties.getProperty("server.port", "3213")

android {
    namespace = "com.raysonstudio.cctv_view"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raysonstudio.cctv_view"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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

            storeFile =
                file(localProperties.getProperty("KEYSTORE_FILE"))

            storePassword =
                localProperties.getProperty("KEYSTORE_PASSWORD")

            keyAlias =
                localProperties.getProperty("KEY_ALIAS")

            keyPassword =
                localProperties.getProperty("KEY_PASSWORD")
        }
    }

    buildTypes {
        getByName("release") {
            buildConfigField("String", "SERVER_IP", "\"${serverIp}\"")
            buildConfigField("String", "SERVER_PORT", "\"${serverPort}\"")
            signingConfig =
                signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
        getByName("debug") {
            buildConfigField("String", "SERVER_IP", "\"${serverIp}\"")
            buildConfigField("String", "SERVER_PORT", "\"${serverPort}\"")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}