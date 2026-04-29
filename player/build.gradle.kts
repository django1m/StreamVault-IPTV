plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.streamvault.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

kover {
    currentProject {
        createVariant("ci") {
            add("debug")
        }
    }
}

dependencies {
    implementation(project(":domain"))

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)  // PE-H03: RTSP stream support
    implementation(libs.media3.exoplayer.smoothstreaming)  // MSS streams
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // FFmpeg software decoders for IPTV codecs (AC3, E-AC3, DTS, MP2, Opus, Vorbis, etc.)
    implementation(libs.nextlib.media3ext)

    // libVLC : moteur de lecture pour les MP4/MKV progressive là où Mp4Extractor
    // de Media3 plante sur des muxes mal formés (moov atom à la fin avec offsets stco invalides).
    implementation(libs.libvlc.all)

    // OkHttp (for custom data source)
    implementation(libs.okhttp)

    // LibVLC (fallback player engine)
    implementation(libs.libvlc.all)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Core
    implementation(libs.core.ktx)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
