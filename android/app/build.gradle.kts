import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val glassQa = providers.gradleProperty("glassQa").map { it == "true" }.orElse(false)

android {
    namespace = "com.glasssign.app"
    // 37 is required by Haze 2.1.0's AAR metadata (minCompileSdk=37). AGP 9.5 documents API 37 as its
    // ceiling, so this is the highest value this toolchain can compile against.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.glasssign.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "0.8.0-preview.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resourceConfigurations += listOf("zh", "en")
    }

    buildTypes {
        debug {
            applicationIdSuffix = if (glassQa.get()) ".glassqa" else ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    testBuildType = if (glassQa.get()) "debug" else "release"
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    lint { xmlReport = true }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

kotlin {
    // AGP 9 built-in Kotlin: this replaces the removed `android.kotlinOptions {}` block. jvmTarget is
    // kept explicit even though AGP can infer it, so the bytecode target cannot drift from compileOptions.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose BOM 2026.09.00 resolves androidx.compose.* to 1.12.1, the same generation as the
    // org.jetbrains.compose.* 1.12.2 artifacts Haze 2.1.0 depends on. That alignment is why the upgrade
    // is needed at all: the previous BOM (2025.11.01) pinned Compose 1.9.5, three minor versions behind.
    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("io.github.kyant0:backdrop:1.0.0")
    implementation("io.github.kyant0:capsule:2.1.1")
    // Haze 2.1.0 Glass: the experimental water-drop dock draws one merged glass surface from a
    // hazeGlassContainer with two real hazeGlass members. Apache-2.0; see THIRD_PARTY_NOTICES.md.
    val hazeVersion = "2.1.0"
    implementation("dev.chrisbanes.haze:haze:$hazeVersion")
    implementation("dev.chrisbanes.haze:haze-glass:$hazeVersion")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(bom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.work:work-testing:2.10.1")
}
