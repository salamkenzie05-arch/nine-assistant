import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Nomor versi dari CI (-PversionCode=<run number>) agar setiap build bisa dipasang menimpa yang lama.
val ciVersionCode = (findProperty("versionCode") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "id.nine.assistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "id.nine.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = ciVersionCode
        versionName = "3.1.$ciVersionCode-beta"

        ndk {
            // Galaxy S23 dan hampir semua HP modern: 64-bit ARM. Memperkecil APK.
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Dua edisi dengan package yang sama (bisa saling menimpa):
    //  - lite = Nine.apk: tanpa layanan aksesibilitas, lolos Google Play Protect saat sideload.
    //  - full = Nine-Full.apk: ditambah layanan aksesibilitas (tombol melayang, on/off Wi-Fi/Bluetooth, putar lagu otomatis);
    //    Play Protect memblokir APK sideload semacam ini kecuali pemindaian Play Protect dimatikan sementara.
    flavorDimensions += "edition"
    productFlavors {
        create("lite") { dimension = "edition" }
        create("full") {
            dimension = "edition"
            versionNameSuffix = "-full"
        }
    }

    signingConfigs {
        // Kunci khusus sideload, sengaja disimpan di repo agar setiap build CI punya tanda tangan
        // yang sama (update bisa dipasang menimpa versi lama). Bukan rahasia; jangan dipakai untuk Play Store.
        create("sideload") {
            storeFile = file("nine-sideload.jks")
            storePassword = "nine-sideload"
            keyAlias = "nine"
            keyPassword = "nine-sideload"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources.excludes += setOf("META-INF/LICENSE*", "META-INF/NOTICE*")
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // Gemma on-device (Google AI Edge LiteRT-LM)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.18.0")

    // Wake word offline (Vosk + JNA versi AAR yang membawa libjnidispatch untuk Android)
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    implementation("net.java.dev.jna:jna:5.13.0@aar")

    testImplementation("junit:junit:4.13.2")
}
