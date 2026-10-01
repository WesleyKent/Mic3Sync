plugins { id("com.android.application") }

android {
    namespace = "dev.openpocket.probe"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "dev.openpocket.mic3sync"
        minSdk = 29
        targetSdk = 35
        versionCode = 17
        versionName = "0.17.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs {
        getByName("debug") {
            val stableDebugKey = rootProject.file("signing/pocketprobe-debug.keystore")
            if (stableDebugKey.isFile) {
                storeFile = stableDebugKey
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies { testImplementation("junit:junit:4.13.2") }
