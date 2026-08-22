plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.berns.linuxports"
    compileSdk = 35

    signingConfigs {
        // Pinned so every build - here, on another machine, or in CI - produces an APK
        // that installs *over* an existing one instead of being refused as a different
        // app. That matters more than usual here: a reinstall would wipe the container
        // and mean downloading and rebuilding the whole distro again.
        //
        // This is the standard Android debug keystore with its published password. It is
        // not a secret and must never be used to sign a release build.
        getByName("debug") {
            storeFile = rootProject.file("keystore/berns-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.berns.linuxports"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // The proot binary, its loaders and its shared libraries are shipped as
            // "libraries" so that Android extracts them into the read-only, exec-allowed
            // nativeLibraryDir. Compressing them is fine, stripping them is not.
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "**/libproot.so",
                "**/libproot_loader.so",
                "**/libproot_loader32.so",
                "**/libtalloc.so",
                "**/libandroid_shmem.so"
            )
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = false
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
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
}
