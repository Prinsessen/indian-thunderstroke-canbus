plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dk.agesen.springfield"
    compileSdk = 36

    defaultConfig {
        applicationId = "dk.agesen.springfield"
        // minSdk 31 (Android 12) on purpose: from API 31 the Bluetooth runtime
        // permissions are BLUETOOTH_SCAN / BLUETOOTH_CONNECT, and with
        // neverForLocation the app needs no location permission at all. Going
        // lower would drag in the legacy BLUETOOTH_ADMIN + ACCESS_FINE_LOCATION
        // path and a second, quite different, permission flow — a lot of code
        // for phones this bike's rider does not own.
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.3"
    }

    // Play upload key. The keystore lives outside every repository, and the
    // password comes from the environment, never from a file that can be
    // committed. Set SPRINGFIELD_KEYSTORE_PW before `gradlew bundleRelease`;
    // without it the release build still compiles but is unsigned, which is
    // fine for a debug session and useless for Play.
    signingConfigs {
        create("release") {
            val ksPath = System.getenv("SPRINGFIELD_KEYSTORE")
                ?: "C:/Users/YourName/Documents/CANFD-MC/play-store/upload-key.jks"
            val ksPw = System.getenv("SPRINGFIELD_KEYSTORE_PW")
            if (ksPw != null) {
                storeFile = file(ksPath)
                storePassword = ksPw
                keyAlias = "upload"
                keyPassword = ksPw
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

// Deliberately minimal. The UI is plain Views rather than Compose: this module's
// job is to prove the BLE decode is correct, and Compose would add a compiler
// plugin whose version has to track Kotlin's exactly — a build-breaking coupling
// with no benefit to a scaffold. JSON uses org.json, which is part of Android.
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // Swipeable pages. The gauges themselves are hand-drawn on Canvas, so this
    // is the only UI dependency — no charting library, and no Compose, whose
    // compiler plugin would have to track the Kotlin version exactly.
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    // Plain JUnit, run on the build machine. Only the two files with no Android
    // in them are covered — the heat curve and the fault-code tables — and that
    // is the point: they are arithmetic and lookup, they decide what the rider
    // is told, and until now the only way to check either was to sit on a
    // motorcycle. Both have shipped a wrong answer that four lines here would
    // have caught.
    testImplementation("junit:junit:4.13.2")
}
