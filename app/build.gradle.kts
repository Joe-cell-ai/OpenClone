import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// A single CI keystore signs downloadable debug and release APKs so Android can
// install later CI builds as updates. The keystore is supplied through GitHub Secrets
// and decoded only under build/; never commit signing material to the repository.
val ciKeystoreFile = layout.buildDirectory.file("generated/ci-signing/openclone-ci.jks").get().asFile
val ciKeystoreBase64 = providers.environmentVariable("OPENCLONE_KEYSTORE_BASE64").orNull
val ciStorePassword = providers.environmentVariable("OPENCLONE_KEYSTORE_PASSWORD").orNull
val ciKeyAlias = providers.environmentVariable("OPENCLONE_KEY_ALIAS").orNull
val ciKeyPassword = providers.environmentVariable("OPENCLONE_KEY_PASSWORD").orNull
val hasCiSigning = !ciKeystoreBase64.isNullOrBlank() &&
    !ciStorePassword.isNullOrBlank() && !ciKeyAlias.isNullOrBlank() &&
    !ciKeyPassword.isNullOrBlank()
if (hasCiSigning) {
    ciKeystoreFile.parentFile.mkdirs()
    ciKeystoreFile.writeBytes(Base64.getDecoder().decode(ciKeystoreBase64!!.trim()))
}
val ciVersionCode = providers.environmentVariable("OPENCLONE_VERSION_CODE")
    .orNull?.toIntOrNull()?.takeIf { it > 0 } ?: 1

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
        versionCode = ciVersionCode
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").res.srcDir(generatedLauncherResDir)
    signingConfigs {
        if (hasCiSigning) {
            create("ci") {
                storeFile = ciKeystoreFile
                storePassword = ciStorePassword
                keyAlias = ciKeyAlias
                keyPassword = ciKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            // Local developer builds retain Android Studio's normal debug key unless
            // the persistent CI key is explicitly provided.
            if (hasCiSigning) signingConfig = signingConfigs.getByName("ci")
        }
        release {
            check(hasCiSigning) {
                "Release builds require OPENCLONE_KEYSTORE_BASE64, OPENCLONE_KEYSTORE_PASSWORD, OPENCLONE_KEY_ALIAS, and OPENCLONE_KEY_PASSWORD."
            }
            signingConfig = signingConfigs.getByName("ci")
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
