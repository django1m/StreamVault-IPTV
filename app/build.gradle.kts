import java.util.Properties
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

fun computeOfficialSigningCertSha256(): String {
    if (!keystorePropertiesFile.exists()) return ""

    val storePath = keystoreProperties.getProperty("storeFile") ?: return ""
    val storePassword = keystoreProperties.getProperty("storePassword") ?: return ""
    val keyAlias = keystoreProperties.getProperty("keyAlias") ?: return ""
    val storeFile = rootProject.file(storePath)
    if (!storeFile.exists()) return ""

    val keyStore = KeyStore.getInstance("JKS")
    storeFile.inputStream().use { input ->
        keyStore.load(input, storePassword.toCharArray())
    }

    val certificate = keyStore.getCertificate(keyAlias) ?: return ""
    return MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString(":") { byte -> "%02X".format(byte) }
}

val officialSigningCertSha256 = computeOfficialSigningCertSha256()

// The two channels version INDEPENDENTLY.
//
// Mobile is offset by +100000 so a phone build can never collide with a TV build
// on the webapp's UNIQUE constraint on apk_versions.version_code, and so the
// variant is obvious at a glance in the admin (the upload form rejects a mobile
// APK below 100000 and a TV one above it).
//
// They are decoupled rather than derived from one another because the mobile UI
// is under active rework and iterates far faster than the TV product; sharing a
// counter would burn TV version numbers on phone-only builds.
val baseVersionCode = 536          // TV channel
val mobileVersionCodeOffset = 100_000
val mobileBuildNumber = 535        // mobile channel

android {
    namespace = "com.streamvault.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.streamvault.app"
        minSdk = 28
        targetSdk = 36
        versionCode = baseVersionCode
        // MUST change on every release, beta included: the updater keys its
        // downloaded-APK cache on versionName (see the mobile flavor comment
        // below). A beta shipping as plain "1.3.37" and then the stable "1.3.37"
        // would make the stable reuse the beta's downloaded file.
        versionName = "1.3.37-beta12"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OFFICIAL_APPLICATION_ID", "\"com.streamvault.app\"")
        buildConfigField("String", "OFFICIAL_SIGNING_CERT_SHA256", "\"$officialSigningCertSha256\"")
        buildConfigField(
            "String",
            "FUZIONTV_BASE_URL",
            "\"${project.findProperty("FUZIONTV_BASE_URL") ?: "https://admin.fuziontv.app"}\""
        )
    }

    // Form-factor variants. The `tv` flavor inherits everything from
    // defaultConfig on purpose: the TV build must stay byte-for-byte equivalent
    // to what is deployed on customer boxes today. All the mobile-specific
    // manifest and theme overrides live in src/mobile/ — there is deliberately
    // no Kotlin source set per flavor, so the two variants can never diverge.
    flavorDimensions += "formfactor"

    productFlavors {
        create("tv") {
            dimension = "formfactor"
            // APP_PLATFORM is what the app reports to /api/v1/device/activate
            // (devices.platform); APP_VARIANT is the APK update channel.
            buildConfigField("String", "APP_VARIANT", "\"tv\"")
            buildConfigField("String", "APP_PLATFORM", "\"androidtv\"")
        }
        create("mobile") {
            dimension = "formfactor"
            versionCode = mobileVersionCodeOffset + mobileBuildNumber
            // The versionName MUST be unique per mobile release, not just the
            // versionCode. The updater identifies releases by versionName:
            // AppUpdateInstaller names the downloaded file
            // "FuzionTV-<versionName>.apk" and pruneStaleDownloadIfNeeded only
            // deletes a stale download when downloadedVersionName differs from
            // the cached one. Shipping several builds as plain "1.3.36" made
            // every release reuse the first downloaded APK, so installing did
            // nothing and the update prompt looped forever.
            versionNameSuffix = "-m$mobileBuildNumber"
            buildConfigField("String", "APP_VARIANT", "\"mobile\"")
            buildConfigField("String", "APP_PLATFORM", "\"androidmobile\"")
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            }
        }
        debug {
            // Use release signing so debug APKs can be installed over release
            // APKs without wiping user data (playlists / preferences).
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        animationsDisabled = true
        // Les classes android.util.* (ex. Log) sont des stubs en test JVM et
        // lèvent "not mocked" par défaut. Renvoyer les valeurs par défaut évite
        // de faire échouer des tests purement logiques (ex. AppStartupCoordinator
        // qui logge via Log.i) sans embarquer Robolectric.
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        rootProject.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

kover {
    currentProject {
        // With a flavor dimension there is no plain "debug" variant any more —
        // only tvDebug and mobileDebug. Coverage tracks the TV build, which is
        // the reference product; the Kotlin sources are shared anyway.
        createVariant("ci") {
            add("tvDebug")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":player"))

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(libs.leakcanary.android)

    // Compose TV
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    // FFmpeg software decoders for IPTV codecs
    implementation(libs.nextlib.media3ext)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // WireGuard userspace tunnel (PIA VPN to bypass the provider's on-demand
    // IP filtering). Apache-2.0 + MIT (wireguard-go) — embeddable in the app.
    implementation(libs.wireguard.tunnel)

    // DataStore for the isolated VPN key/state store (VpnStore).
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    // Activity & Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Palette extraction for ambient backgrounds
    implementation(libs.palette)

    // Baseline profile runtime installer
    implementation(libs.profileinstaller)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.documentfile)
    implementation(libs.coroutines.android)
    implementation(libs.appcompat)
    implementation(libs.mediarouter)
    implementation(libs.play.services.cast.framework)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
