plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ma.santojan.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ma.khalid.dalil"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "6.4"
    }
    signingConfigs {
        create("rel") {
            storeFile = rootProject.file("santojan.jks")
            storePassword = "santojan123"
            keyAlias = "santojan"
            keyPassword = "santojan123"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("rel")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}
