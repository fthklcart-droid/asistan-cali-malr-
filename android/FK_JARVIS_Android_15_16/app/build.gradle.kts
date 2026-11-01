import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.fkartmedia.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fkartmedia.jarvis"
        minSdk = 35          // Android 15
        targetSdk = 36       // Android 16
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        // Test anahtarı (depoda): güncellemelerin aynı imzayla kurulabilmesi için sabit.
        // Play Store'a yüklemek için gizli bir üretim anahtarı kullanılmalıdır (FKJ_* ortam değişkenleri).
        create("fkjarvis") {
            storeFile = file(System.getenv("FKJ_KEYSTORE") ?: "fkjarvis-test.jks")
            storePassword = System.getenv("FKJ_STORE_PASSWORD") ?: "fkjarvis-test"
            keyAlias = System.getenv("FKJ_KEY_ALIAS") ?: "fkjarvis"
            keyPassword = System.getenv("FKJ_KEY_PASSWORD") ?: "fkjarvis-test"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fkjarvis")
        }
        debug {
            signingConfig = signingConfigs.getByName("fkjarvis")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
}
