plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.chaquopy)
}

android {
    namespace = "com.zemin.downloader"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.ricardo.douyindown"
        minSdk = 28
        targetSdk = 36
        versionCode = 11
        versionName = "2.3.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("android") {
            storeFile = file("./debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("android")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("android")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.dynamicanimation)
    implementation(libs.coil)
    implementation(libs.coil.network.okhttp)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // 协程
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)

    // Lifecycle
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)

    // 序列化
    implementation(libs.moshi.kotlin)

}

chaquopy {
    defaultConfig {
        version = "3.12"
        providers.gradleProperty("chaquopy.buildPython").orNull?.let { buildPython(it) }
        pip {
            install("aiohttp>=3.9.0")
            install("aiofiles>=23.2.1")
            install("aiosqlite>=0.19.0")
            install("pyyaml>=6.0.1")
            install("python-dateutil>=2.8.2")
            install("gmssl>=3.2.2")
            install("httpx[http2]>=0.28.1")
            install("lxml==5.3.0")
            install("emoji>=2.15.0")
            install("rich>=14.0.0")
        }
    }
}
