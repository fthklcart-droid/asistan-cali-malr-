import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Üretim imzası: depo dışında tutulan keystore.properties dosyasından ya da SRIUS_* ortam değişkenlerinden okunur.
val signingProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = signingProps.getProperty(key) ?: System.getenv(env)
val releaseStore = signingValue("storeFile", "SRIUS_KEYSTORE")

// Google Play: her yüklemede sürüm kodu artmalı. CI'da çalıştırma numarası kullanılır (yerelde 2).
val ciVersionCode = (System.getenv("SRIUS_VERSION_CODE") ?: System.getenv("GITHUB_RUN_NUMBER"))?.toIntOrNull()
// Play'de paket adı bir kez yayınlanınca değişmez; kendi adını vermek için: -PappId=com.senin.adin.asistan
val appId = (findProperty("appId") as String?) ?: "com.fk.jarvis"

android {
    namespace = "com.fk.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 36          // Android 16
        versionCode = 100 + (ciVersionCode ?: 2)
        versionName = "0.3.${ciVersionCode ?: 0}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Test anahtarı (depoda, gizli değil): debug APK'ların her derlemede aynı imzayla güncellenebilmesi için.
        create("test") {
            storeFile = rootProject.file("signing/sirius-test.jks")
            storePassword = "sirius-test"
            keyAlias = "sirius"
            keyPassword = "sirius-test"
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
        if (releaseStore != null) {
            create("release") {
                storeFile = rootProject.file(releaseStore)
                storePassword = signingValue("storePassword", "SRIUS_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SRIUS_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SRIUS_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("test")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Üretim anahtarı verilmediyse test anahtarıyla imzalanır: telefona doğrudan kurulabilir,
            // hata ayıklanamaz (debuggable=false) release APK. Play Store için kendi anahtarını kullan.
            signingConfig = signingConfigs.getByName(if (releaseStore != null) "release" else "test")
        }
    }
    // İki sürüm: "tam" (erişilebilirlik ile ekran kontrolü dahil) ve "basit" (erişilebilirlik hizmeti yok).
    // Bazı telefonlarda Google Play Protect, dışarıdan yüklenen ve erişilebilirlik hizmeti içeren uygulamaları
    // engelleyebilir; o durumda "basit" sürüm kurulabilir.
    flavorDimensions += "surum"
    productFlavors {
        create("tam") {
            dimension = "surum"
            buildConfigField("boolean", "SCREEN_CONTROL", "true")
        }
        create("basit") {
            dimension = "surum"
            buildConfigField("boolean", "SCREEN_CONTROL", "false")
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
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/INDEX.LIST",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/*.version",
            )
        }
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        textReport = true
        textOutput = file("build/reports/lint.txt")
        // Yalnızca gerçek sorunları raporla (CI günlüğünde okunabilir kalsın)
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "ObsoleteLintCustomCheck")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Takip edilen konular için günlük arka plan kontrolü
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    // Claude sağlayıcısı: resmi Anthropic Java SDK'sı
    implementation("com.anthropic:anthropic-java:3.3.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
