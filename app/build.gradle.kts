plugins { id("com.android.application") }

android {
    namespace = "io.github.tanay7.photocleaner"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.tanay7.photocleaner"
        minSdk = 36      // Android 16
        targetSdk = 37   // Android 17
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt() // grows every CI build, so updates install in place
        versionName = "1.1"
    }
    // One stable key for every build (CI included), so new APKs install over old ones and keep your
    // folder choice and the fingerprint cache. It's committed, so it's only fit for sideloading.
    signingConfigs {
        create("sideload") {
            storeFile = file("photo-cleaner.keystore")
            storePassword = "photocleaner"
            keyAlias = "cleaner"
            keyPassword = "photocleaner"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("sideload") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("sideload")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
        textReport = true
        textOutput = file("build/reports/lint.txt")
        disable += "SetTextI18n" // the app is English-only and builds its texts from counts
    }
    packaging { resources.excludes += listOf("kotlin/**", "META-INF/*.kotlin_module", "META-INF/*.version", "DebugProbesKt.bin") }
}
// No libraries in the APK: the Android framework and the Kotlin standard library only.
dependencies {
    testImplementation("junit:junit:4.13.2") // JVM unit tests only, not in the APK
}
