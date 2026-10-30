import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.keepitsimple"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.keepitsimple"
        minSdk = 26
        targetSdk = 35
        versionName = "0.8"
        // versionCode déduit de versionName (0.3 -> 300) : chaque version s'installe par-dessus la précédente
        versionCode = versionName!!.split(".").map { it.toInt() }.let { it[0] * 10000 + it[1] * 100 + it.getOrElse(2) { 0 } }
        val props = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        buildConfigField("String", "MS_CLIENT_ID", "\"${props.getProperty("MS_CLIENT_ID", "")}\"")
    }
    buildFeatures { buildConfig = true }
    // Clé de debug versionnée : même SHA-1 partout (local et CI), requis par l'ID client Google Android
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    testImplementation("junit:junit:4.13.2")
}
