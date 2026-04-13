plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
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

android {
    namespace = "org.whcanrc.pewcast"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "org.whcanrc.pewcast"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
}
