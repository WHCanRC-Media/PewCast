plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingEnv = mapOf(
    "PEWCAST_KEYSTORE" to System.getenv("PEWCAST_KEYSTORE"),
    "PEWCAST_KEYSTORE_PASSWORD" to System.getenv("PEWCAST_KEYSTORE_PASSWORD"),
    "PEWCAST_KEY_ALIAS" to System.getenv("PEWCAST_KEY_ALIAS"),
    "PEWCAST_KEY_PASSWORD" to System.getenv("PEWCAST_KEY_PASSWORD"),
)
val hasSigningEnv = signingEnv.values.all { !it.isNullOrBlank() }

gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { t ->
        t.name.contains("Release") &&
            (t.name.startsWith("assemble") || t.name.startsWith("bundle") || t.name.startsWith("package"))
    }
    if (wantsRelease && !hasSigningEnv) {
        val missing = signingEnv.filter { it.value.isNullOrBlank() }.keys
        throw GradleException(
            "Release build requires signing env vars. Missing: ${missing.joinToString(", ")}. " +
                "Set them (e.g. source ~/.config/pewcast/signing.env) before running assembleRelease."
        )
    }
}

// Generate launcher icons from source PNG
tasks.register("generateIcons") {
    val sourceIcon = rootProject.file("../icon.png")
    val resDir = file("src/main/res")
    val sizes = mapOf(
        "mipmap-mdpi" to 48,
        "mipmap-hdpi" to 72,
        "mipmap-xhdpi" to 96,
        "mipmap-xxhdpi" to 144,
        "mipmap-xxxhdpi" to 192
    )

    inputs.file(sourceIcon)
    outputs.files(sizes.map { (dir, _) -> file("$resDir/$dir/ic_launcher.png") })

    doLast {
        sizes.forEach { (dir, size) ->
            val outDir = file("$resDir/$dir")
            outDir.mkdirs()
            exec {
                commandLine("convert", sourceIcon.absolutePath, "-resize", "${size}x${size}", "$outDir/ic_launcher.png")
            }
        }
    }
}

tasks.named("preBuild") {
    dependsOn("generateIcons")
}

// Stamp the APK with the git identity of the android/ tree so the app can
// compare against the server's /status response. Falls back to "unknown" / 0
// when git is unavailable (e.g., building from an unpacked tarball).
fun runGit(vararg args: String): String? {
    return try {
        val p = ProcessBuilder(listOf("git") + args.toList())
            .directory(rootProject.projectDir.parentFile)
            .redirectErrorStream(false)
            .start()
        val out = p.inputStream.bufferedReader().readText().trim()
        if (p.waitFor() == 0 && out.isNotEmpty()) out else null
    } catch (_: Exception) {
        null
    }
}

val apkGitSha: String = runGit("log", "-1", "--format=%H", "--", "android") ?: "unknown"
val apkCommitCount: Int = runGit("rev-list", "--count", "HEAD", "--", "android")?.toIntOrNull() ?: 0

android {
    namespace = "org.whcanrc.pewcast"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "org.whcanrc.pewcast"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1"

        buildConfigField("String", "APK_GIT_SHA", "\"$apkGitSha\"")
        buildConfigField("int", "APK_COMMIT_COUNT", "$apkCommitCount")

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            if (hasSigningEnv) {
                storeFile = file(signingEnv["PEWCAST_KEYSTORE"]!!)
                storePassword = signingEnv["PEWCAST_KEYSTORE_PASSWORD"]
                keyAlias = signingEnv["PEWCAST_KEY_ALIAS"]
                keyPassword = signingEnv["PEWCAST_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Activity + Compose integration
    implementation("androidx.activity:activity-compose:1.9.3")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Core
    implementation("androidx.core:core-ktx:1.15.0")

    // MediaSession + MediaStyle notification
    implementation("androidx.media:media:1.7.0")
}
