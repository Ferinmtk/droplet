import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing material lives outside the repo. With the properties file
// present, release builds are signed with the real key; without it (a fresh
// clone, CI) they fall back to the debug key so the build still works.
val signingFile = file(
    System.getenv("DROPLET_SIGNING")
        ?: "${System.getProperty("user.home")}/.android/droplet-release.properties"
)
val signing = Properties().apply {
    if (signingFile.isFile) signingFile.inputStream().use { load(it) }
}

android {
    namespace = "dev.droplet.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.droplet.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.4"
    }

    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 stays off: the app is small, and shrinking would need keep
            // rules for the WebView JavaScript bridge that nobody has verified
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric runs the app's own code on the JVM (see src/test)
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric fits in this; left unset, the test JVM may take a quarter of the RAM.
            // -PtestHeap=… overrides it; one test JVM at a time
            it.maxHeapSize = (project.findProperty("testHeap") as String?) ?: "768m"
            it.maxParallelForks = 1
            // the live-connection tests talk to a real hub when one is given
            it.systemProperty("droplet.testHub", System.getenv("DROPLET_TEST_HUB") ?: "")
            // the local-first tests: a second hub with DROPLET_PIN, and a "clone" that
            // shares the first hub's id with a different certificate (see LocalFirstHubTest)
            it.systemProperty("droplet.testPinHub", System.getenv("DROPLET_TEST_PIN_HUB") ?: "")
            it.systemProperty("droplet.testPin", System.getenv("DROPLET_TEST_PIN") ?: "")
            it.systemProperty("droplet.testCloneHub", System.getenv("DROPLET_TEST_CLONE_HUB") ?: "")
            // the mesh interop tests: a venv Python with ./agent installed, the hub's pid (the
            // roster test stops it), and optionally the LAN address and a scratch folder
            it.systemProperty("droplet.testAgentPy", System.getenv("DROPLET_TEST_AGENT_PY") ?: "")
            it.systemProperty("droplet.testHubPid", System.getenv("DROPLET_TEST_HUB_PID") ?: "")
            it.systemProperty("droplet.testLanIp", System.getenv("DROPLET_TEST_LAN_IP") ?: "")
            it.systemProperty("droplet.testScratch", System.getenv("DROPLET_TEST_SCRATCH") ?: "")
            // a real peer to shake hands with, and nothing more: "host:port:fingerprint"
            it.systemProperty("droplet.testRealPeer", System.getenv("DROPLET_TEST_REAL_PEER") ?: "")
            // the TV remote against tests/fake_tv.py: a Python with androidtvremote2 (the hub's venv)
            it.systemProperty("droplet.testTvPy", System.getenv("DROPLET_TEST_TV_PY") ?: "")
            // screens rendered for review land here when set (see ScreensTest)
            it.systemProperty("droplet.shots", System.getenv("DROPLET_SHOTS") ?: "")
        }
    }

    lint {
        // the targetSdk is a deliberate choice (see README), not an oversight
        disable += "OldTargetApi"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core-ktx:1.6.1")
}
