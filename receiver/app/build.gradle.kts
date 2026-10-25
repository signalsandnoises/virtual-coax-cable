plugins {
    // AGP 9 has Kotlin built in; no separate kotlin-android plugin.
    id("com.android.application")
}

android {
    namespace = "com.vcx.receiver"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.vcx.receiver"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1-m1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
