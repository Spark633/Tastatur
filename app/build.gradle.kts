plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.spark633.tastatur"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.spark633.tastatur"
        minSdk = 26
        targetSdk = 35
        // Bei jedem Build auf GitHub steigt die Versionsnummer automatisch.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    signingConfigs {
        // Fester Schlüssel im Repo, damit sich jede neue APK als Update
        // über die alte installieren lässt.
        create("fest") {
            storeFile = file("tastatur.keystore")
            storePassword = "tastatur"
            keyAlias = "tastatur"
            keyPassword = "tastatur"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fest")
        }
        debug {
            signingConfig = signingConfigs.getByName("fest")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
