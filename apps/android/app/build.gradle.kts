plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// ---------------------------------------------------------------------------------------------
// Live2D Cubism SDK (closed-source, never committed). See ../live2d-core/README.md.
// The Cubism backend source set is compiled only when BOTH the Core library and the
// CubismJavaFramework sources are present locally; otherwise the app ships the procedural
// placeholder renderer and everything else (overlay, FPS governor, hit-testing) still works.
// ---------------------------------------------------------------------------------------------
val live2dCoreDir: File = rootProject.file("live2d-core")
val cubismCoreArtifacts: List<File> = live2dCoreDir.listFiles()
    ?.filter { it.isFile && it.name.startsWith("Live2DCubismCore") && (it.extension == "jar" || it.extension == "aar") }
    .orEmpty()
val cubismFrameworkSrc: File = File(live2dCoreDir, "framework/src/main/java")
val cubismJniLibs: File = File(live2dCoreDir, "jniLibs")
val cubismEnabled: Boolean = cubismCoreArtifacts.isNotEmpty() && cubismFrameworkSrc.isDirectory

android {
    namespace = "com.neko.secretary"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.neko.secretary"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "CUBISM_ENABLED", cubismEnabled.toString())
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    sourceSets {
        getByName("main") {
            if (cubismEnabled) {
                java.srcDir("src/cubism/java")
                java.srcDir(cubismFrameworkSrc)
            }
            if (cubismJniLibs.isDirectory) {
                jniLibs.srcDir(cubismJniLibs)
            }
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
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.ktor:ktor-client-okhttp:3.0.1")
    implementation("io.ktor:ktor-client-websockets:3.0.1")
    // Live2D Cubism Core (Live2DCubismCore.aar / .jar) placed externally in ../live2d-core/ (gitignored)
    if (cubismCoreArtifacts.isNotEmpty()) {
        implementation(files(cubismCoreArtifacts))
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

if (cubismEnabled) {
    logger.lifecycle("Neko: Live2D Cubism SDK detected -> CubismModelBackend enabled")
} else {
    logger.lifecycle("Neko: Live2D Cubism SDK not found in live2d-core/ -> placeholder renderer only")
}
