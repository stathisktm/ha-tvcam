plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.pestpoint.radioauto.tvcam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pestpoint.radioauto.tvcam"
        // Deliberately higher than the other modules' minSdk 23 —
        // WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY (the type this whole app exists to
        // use) only exists from API 26 onward; no reason to carry pre-26 fallback code here.
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor calls java.net.URLEncoder/URLDecoder's Charset-taking overloads, which
        // only exist on the platform starting at API 33 — without desugaring, resolving a YouTube
        // video_url throws NoSuchMethodError on this exact TV (Android 12 / API 31). Same fix
        // already applied and confirmed working in the tv module for the same reason.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Compose is only used for the one-screen setup Activity — the overlay itself is a plain
    // WindowManager-managed View tree (see OverlayWindowController), not Compose, since hosting a
    // ComposeView inside a Service-owned window means manually wiring a LifecycleOwner /
    // SavedStateRegistryOwner / ViewModelStoreOwner that Compose normally gets for free from an
    // Activity — not worth it for a PlayerView with no interactive UI.
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Same versions already pinned in tv/build.gradle.kts — the overlay's video surface is a
    // plain PlayerView fed an HLS MediaItem, the same mechanism TvPlaybackService already uses
    // for IPTV. No media3-session: this app never exposes a MediaSession/MediaController, the
    // ExoPlayer instance is purely internal to CameraOverlayService.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    // A user-supplied video URL (as opposed to an HA camera entity resolved to HLS) is just as
    // likely to be a plain local-network camera's own RTSP feed as an HLS/HTTP one — ExoPlayer
    // auto-selects RtspMediaSource for rtsp:// URIs purely from this artifact being present on the
    // classpath, no extra wiring needed beyond adding it.
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Built-in WebSocket support (OkHttpClient.newWebSocket) — no separate dependency needed for
    // talking to Home Assistant's WebSocket API.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Same version already pinned in tv/build.gradle.kts — resolves a YouTube video_url into an
    // actual playable stream URL (a youtube.com/watch link on its own isn't media, just a page).
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    testImplementation("junit:junit:4.13.2")
}
