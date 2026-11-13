plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing material is supplied only by the CI environment. The decoded
// keystore is written under build/ (never into the source tree) and is not committed.
val releaseKeystoreFile = layout.buildDirectory.file("generated/release-signing/openclone-release.jks").get().asFile
val releaseKeystoreBase64 = providers.environmentVariable("OPENCLONE_KEYSTORE_BASE64").orNull
if (!releaseKeystoreBase64.isNullOrBlank()) {
    releaseKeystoreFile.parentFile.mkdirs()
    releaseKeystoreFile.writeBytes(java.util.Base64.getDecoder().decode(releaseKeystoreBase64.trim()))
}

val arsclibOriginal: Configuration by configurations.creating { isTransitive = false }

val strippedArsclib = tasks.register<Jar>("strippedArsclib") {
    archiveFileName.set("arsclib-stripped.jar")
    destinationDirectory.set(layout.buildDirectory.dir("stripped-libs"))
    from({ arsclibOriginal.map { zipTree(it) } }) {
        exclude("android/**", "org/xmlpull/**")
    }
}

// Generate a drawable resource from the user-supplied master icon in assets.
// Keeping the source PNG untouched avoids lossy re-encoding in the repository.
val generatedLauncherResDir = layout.buildDirectory.dir("generated/opencloneLauncherRes")
val generateOpenCloneLauncher = tasks.register<Copy>("generateOpenCloneLauncher") {
    from(layout.projectDirectory.file("src/main/assets/openclone-icon-original.png"))
    into(generatedLauncherResDir.map { it.dir("drawable-nodpi") })
    rename { "openclone_launcher.png" }
}

android {
    namespace = "org.openclone.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.openclone.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").res.srcDir(generatedLauncherResDir)
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

tasks.named("preBuild").configure {
    dependsOn(generateOpenCloneLauncher)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.03.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core:1.6.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    // WorkManager exposes ListenableFuture in its public API; the 1.0 artifact is a
    // compatibility stub in some dependency graphs, so include the actual Guava API.
    implementation("com.google.guava:guava:33.4.8-android")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.android.tools.build:apksig:8.9.1")
    implementation("org.smali:dexlib2:2.5.2")
    arsclibOriginal("io.github.reandroid:ARSCLib:1.4.0")
    implementation(files(strippedArsclib))
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
}
