import com.android.build.gradle.internal.api.BaseVariantOutputImpl

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

// 从 git tag 获取版本信息
fun getVersionCode(): Int {
    val code = project.findProperty("VERSION_CODE")?.toString()?.toIntOrNull()
    if (code != null) return code
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--abbrev=0"))
        val tag = process.inputStream.bufferedReader().readText().trim().removePrefix("v")
        val parts = tag.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val patch = parts.getOrNull(2)?.toIntOrNull() ?: 0
        major * 10000 + minor * 100 + patch
    } catch (e: Exception) {
        1 // 默认值
    }
}

fun getVersionName(): String {
    val name = project.findProperty("VERSION_NAME")?.toString()
    if (!name.isNullOrBlank()) return name
    return try {
        val process = Runtime.getRuntime().exec(arrayOf("git", "describe", "--tags", "--abbrev=0"))
        process.inputStream.bufferedReader().readText().trim().removePrefix("v")
    } catch (e: Exception) {
        "1.0.0"
    }
}

android {
    namespace = "com.xzygis.silentguard"
    compileSdk = 34

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_FILE")
            if (keystorePath != null) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("STORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "com.xzygis.silentguard"
        minSdk = 26
        targetSdk = 34
        versionCode = getVersionCode()
        versionName = getVersionName()

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{NOTICE.md,LICENSE.md}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }

    applicationVariants.all {
        val apkVersionName = versionName
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName = "silentguard-$apkVersionName.apk"
        }
    }
}

dependencies {
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.01.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // Google Play Services Location
    implementation("com.google.android.gms:play-services-location:21.1.0")

    // Jakarta Mail (SMTP)
    implementation("com.sun.mail:jakarta.mail:2.0.1") {
        exclude(group = "jakarta.activation")
    }
    implementation("com.sun.activation:jakarta.activation:2.0.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // Security (EncryptedSharedPreferences)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3")

    // 高德地图 SDK（注意：v10.x 的 so 尚未适配 16KB page alignment，已通过 useLegacyPackaging 规避）
    implementation("com.amap.api:3dmap:10.0.600")

    testImplementation("junit:junit:4.13.2")
}
