import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.rdald.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.rdald.app"
        minSdk = 31
        targetSdk = 34
        versionCode = 142
        versionName = "0.8.7"
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        // F-Droid builds `release` from source and signs it with its own key, so this is
        // intentionally UNSIGNED. Prepare it with `./gradlew assembleFdroid`.
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Local, installable release signed with the debug key (updates in place, keeps data).
        create("selfsigned") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        viewBinding = true
    }
}

// Prepare an unsigned APK for F-Droid (F-Droid signs it with its own key).
//   ./gradlew assembleFdroid
tasks.register("assembleFdroid") {
    group = "fdroid"
    description = "Assemble the unsigned release APK for F-Droid and give it a clear name."
    dependsOn("assembleRelease")
    doLast {
        val dir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val files = dir.listFiles() ?: emptyArray()
        val src = files.firstOrNull { it.isFile && it.name == "app-release-unsigned.apk" }
            ?: files.firstOrNull { it.isFile && it.name.endsWith(".apk") }
        if (src != null) {
            val out = File(dir, "NaraTTS-${android.defaultConfig.versionName}-fdroid-unsigned.apk")
            src.copyTo(out, overwrite = true)
            println("F-Droid APK ready: ${out.absolutePath}")
        } else {
            println("No release APK found in ${dir.absolutePath}")
        }
    }
}

dependencies {
    // Core AndroidX - compatible with AGP 8.3.0 and compileSdk 34
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    
    // Media - AntennaPod uses both androidx.media (for MediaSessionCompat) AND media3
    implementation("androidx.media:media:1.6.0")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-common:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    
    // Lifecycle & ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    
    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Preferences
    implementation("androidx.preference:preference-ktx:1.2.1")
    
    // Existing deps
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("net.dankito.readability4j:readability4j:1.0.8")
}