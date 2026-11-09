import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val cloudVisionApiKey: String =
    (localProps.getProperty("CLOUD_VISION_API_KEY") ?: System.getenv("CLOUD_VISION_API_KEY") ?: "")
        .trim()

android {
    namespace = "com.derricklee.ankidict"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.derricklee.ankidict"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String",
            "CLOUD_VISION_API_KEY",
            "\"${cloudVisionApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"",
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/CONTRIBUTORS.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE.md",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")
    implementation("com.google.mlkit:text-recognition-japanese:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.1")
}
