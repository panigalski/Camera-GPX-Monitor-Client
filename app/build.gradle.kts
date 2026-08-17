
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}



android {
    namespace = "com.labpano.gpxclient"
    compileSdk = 33
    buildToolsVersion = "30.0.3"
    defaultConfig {
        applicationId = "com.labpano.gpxclient"
        minSdk = 24
        targetSdk = 33
        versionCode = 76
        versionName = "1.10.29"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { buildConfig = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Local JVM tests cannot execute Android's stub org.json implementation.
    // Use the real JSON-java implementation only on the unit-test runtime classpath.
    testImplementation("org.json:json:20231013")
}
