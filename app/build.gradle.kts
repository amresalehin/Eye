plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.chaquo.python")
}

android {
    namespace = "com.amresalehin.emregallery"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.amresalehin.emregallery"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        if (project.findProperty("buildLlamaNative")?.toString()?.toBoolean() == true) {
            externalNativeBuild {
                cmake {
                    targets += listOf("emre_llama_vlm")
                }
            }
        }
    }

    buildTypes {
        debug {
            // Debug build configuration
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/*.version"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }

    // Opt-in native llama.cpp + libmtmd build for on-device GGUF + mmproj VLM inference.
    // Pass `-PbuildLlamaNative=true` (requires Android NDK + CMake 3.22+) to bundle libemre_llama_vlm.so.
    if (project.findProperty("buildLlamaNative")?.toString()?.toBoolean() == true) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    // Robolectric downloads Android framework jars at test runtime. Use the
    // Google-hosted Maven Central mirror instead of repo1.maven.org, which
    // is intermittently unreachable from hosted CI runners.
    testOptions {
        unitTests.all {
            it.systemProperty(
                "robolectric.dependency.repo.url",
                "https://maven-central.storage-download.googleapis.com/maven2"
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.5.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("io.coil-kt:coil-svg:2.7.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // On-device InsightFace buffalo_l SCRFD detector and ArcFace inference.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    // On-device Vision-Language Model (VLM) runtime (MediaPipe / LiteRT-LM for Gemma 3n / Gemma 4).
    implementation("com.google.mediapipe:tasks-genai:0.10.27")
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    // Native Tesseract engine and its JNI libraries are packaged in the APK.
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
    // On-device Google ML Kit Text Recognition with multiple language script support
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-korean:16.0.1")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

chaquopy {
    defaultConfig {
        version = "3.10"
        pip {
            install("gallery-dl")
            install("yt-dlp")
        }
    }
}
