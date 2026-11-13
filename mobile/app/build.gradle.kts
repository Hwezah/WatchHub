plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/** From mobile/.env, so keys stay out of git: the first of [names] set. */
fun env(vararg names: String): String = rootProject.file(".env").takeIf { it.exists() }?.readLines().orEmpty()
    .map { it.trim() }
    .firstOrNull { line -> names.any { line.startsWith("$it=") } }
    ?.substringAfter('=')?.trim().orEmpty()

// The Expo name still works.
val subdlKey = env("SUBDL_KEY", "EXPO_PUBLIC_SUBDL_KEY")

android {
    namespace = "com.torrentviewer.mobile"
    compileSdk = 37

    defaultConfig {
        // This fork's own id, so it never installs over (or is replaced by) upstream Watchly.
        // The code's package (namespace) stays com.torrentviewer.mobile.
        applicationId = "com.hwezah.watchhub"
        minSdk = 24
        targetSdk = 36
        versionCode = 6
        versionName = "2.4.0"
        buildConfigField("String", "SUBDL_KEY", "\"$subdlKey\"")
        // Public ids, no secrets: both sign in by device code.
        buildConfigField("String", "SIMKL_CLIENT_ID", "\"${env("SIMKL_CLIENT_ID")}\"")
        buildConfigField("String", "MDBLIST_CLIENT_ID", "\"${env("MDBLIST_CLIENT_ID")}\"")
        buildConfigField("String", "TMDB_KEY", "\"${env("TMDB_KEY")}\"")
        buildConfigField("long", "BUILD_TIME", "${System.currentTimeMillis()}L")
        manifestPlaceholders["appLabel"] = "WatchHub"
    }

    // libtorrent4j and libVLC each ship a large .so per ABI: one APK per ABI.
    // -PuniversalApk adds one APK with every ABI, for the release page.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86")
            isUniversalApk = providers.gradleProperty("universalApk").isPresent
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        // What CI builds for the phone: beside a release build rather than over it. Signed with the
        // committed debug.keystore so every CI build installs over the last one, keeping your data.
        debug {
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "WatchHub"
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            // Off, as in CloudStream itself: .cs3 plugins call the library and its dependencies by name
            // at run time, and R8 would rename or drop whatever this app's own code doesn't use.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
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

    // Native libraries compressed in the APK (extracted on install): libVLC, libtorrent and libc++ are
    // ~65 MB per ABI uncompressed, which made a 100 MB phone APK and a 277 MB universal one.
    packaging {
        jniLibs { useLegacyPackaging = true }
        // The same licence and OSGi files come in several of the plugin library's jars.
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/INDEX.LIST",
            "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/*.md",
        )
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    // Google Cast: the Expo build's version, with MediaRouter's device chooser.
    implementation("com.google.android.gms:play-services-cast-framework:21.5.0")
    // Only for the AppCompat theme that chooser dialog insists on.
    implementation("androidx.appcompat:appcompat:1.7.1")
    // CloudStream plugins' settings screens are Material bottom sheets (StreamPlay, ShowBox…), as in CloudStream.
    implementation("com.google.android.material:material:1.12.0")

    // CloudStream plugins (.cs3): the library they are compiled against — MainAPI, extractors, the `app`
    // HTTP client — pinned to the commit of CloudStream's "pre-release" tag that current plugins target.
    // Bump it when plugins start failing with NoSuchMethodError / NoClassDefFoundError.
    implementation("com.github.recloudstream.cloudstream:library:5d66bbeaf3")
    implementation("com.github.Blatzar:NiceHttp:0.4.18")
    // TLS for older Androids, as CloudStream bundles.
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    testImplementation("junit:junit:4.13.2")
    // org.json is an Android stub on the JVM; the real one for unit tests.
    testImplementation("org.json:json:20240303")
}
