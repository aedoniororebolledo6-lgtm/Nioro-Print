plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNum: Int = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.nioro.print"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.nioro.print"
        minSdk = 26
        targetSdk = 34
        versionCode = buildNum
        versionName = "1.0.$buildNum"
    }

    signingConfigs {
        create("nioro") {
            storeFile = file("nioro-print.keystore")
            storePassword = "nioroprint"
            keyAlias = "nioro"
            keyPassword = "nioroprint"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("nioro")
        }
        debug {
            signingConfig = signingConfigs.getByName("nioro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
