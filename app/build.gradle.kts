
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
        versionCode = 73
        versionName = "1.10.26"
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
}
