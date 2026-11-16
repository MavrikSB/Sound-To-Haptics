import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.sound.to.haptics"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.sound.to.haptics"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "3.0"

        ndk {
            abiFilters += listOf(
                "arm64-v8a",
            )
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    flavorDimensions += "musicIntegration"

    productFlavors {
        create("offlineOnly") {
            dimension = "musicIntegration"
        }

        create("withBitChord") {
            dimension = "musicIntegration"
        }
    }

    signingConfigs {
        create("release") {
            val localProps = Properties().apply {
                runCatching { FileInputStream(rootProject.file("local.properties")).use { load(it) } }
            }
            val relStoreFile = localProps.getProperty("release.storeFile")
            if (!relStoreFile.isNullOrBlank()) {
                storeFile = rootProject.file(relStoreFile)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
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

            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                signingConfig = signingConfigs.getByName("debug")
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// NewPipeExtractor ships org.schabi.newpipe.extractor.utils.Utils; this module

val newPipeExtractorRaw: Configuration by configurations.creating {
    isTransitive = false
    isCanBeConsumed = false
}
dependencies {
    newPipeExtractorRaw("com.github.TeamNewPipe:NewPipeExtractor:v0.26.3")
}
val newPipeExtractorStripped = tasks.register<org.gradle.api.tasks.bundling.Jar>(
    "stripNewPipeExtractorUtils"
) {
    archiveFileName.set("NewPipeExtractor-v0.26.3-noutils.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from(provider { newPipeExtractorRaw.map { zipTree(it) } }) {

        exclude("org/schabi/newpipe/extractor/utils/Utils.class")
        exclude("org/schabi/newpipe/extractor/utils/Utils\$*.class")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.1")
    implementation("androidx.activity:activity-compose:1.9.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.1")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("com.google.android.material:material:1.12.0")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("com.moizhassan.ffmpeg:ffmpeg-kit-16kb:6.1.1")
    implementation("io.hapticlabs:hapticlabsplayer:0.6.3")

    "withBitChordImplementation"("androidx.media3:media3-exoplayer:1.11.0")
    "withBitChordImplementation"("androidx.media3:media3-session:1.11.0")
    "withBitChordImplementation"("androidx.media3:media3-common:1.11.0")
    "withBitChordImplementation"("androidx.media3:media3-datasource-okhttp:1.11.0")

    "withBitChordImplementation"("io.ktor:ktor-client-core:3.6.0")
    "withBitChordImplementation"("io.ktor:ktor-client-okhttp:3.6.0")
    "withBitChordImplementation"("io.ktor:ktor-client-content-negotiation:3.6.0")
    "withBitChordImplementation"("io.ktor:ktor-serialization-kotlinx-json:3.6.0")
    "withBitChordImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.12.0")
    "withBitChordImplementation"("com.squareup.okhttp3:okhttp:4.12.0")

    "withBitChordImplementation"("com.github.MetrolistGroup.innertubex:innertubex-android:v0.11.7")

    // newPipeExtractorStripped task above; the transitive dependencies it would

    "withBitChordImplementation"(files(newPipeExtractorStripped))
    "withBitChordImplementation"("com.github.TeamNewPipe:nanojson:e9d656ddb49a412a5a0a5d5ef20ca7ef09549996")
    "withBitChordImplementation"("org.jsoup:jsoup:1.22.2")
    "withBitChordImplementation"("com.google.code.findbugs:jsr305:3.0.2")
    "withBitChordImplementation"("com.google.protobuf:protobuf-javalite:4.35.0")
    "withBitChordImplementation"("org.mozilla:rhino:1.8.1")
    "withBitChordImplementation"("org.mozilla:rhino-engine:1.8.1")

    "withBitChordImplementation"("androidx.security:security-crypto:1.1.0-alpha06")

    "withBitChordImplementation"("androidx.collection:collection-ktx:1.4.5")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
