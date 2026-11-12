plugins {
    id("com.android.application")
}

android {
    namespace = "com.s410211.a55wuwa"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.s410211.a55wuwa"
        minSdk = 34
        targetSdk = 37
        versionCode = 9
        versionName = "0.8.0"
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("androidx.annotation:annotation:1.3.0")

    testImplementation("junit:junit:4.13.2")
}
