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

// ---------------------------------------------------------------------------
// mining.db backup guard (see scripts/backup_mining_db.py)
//
// mining.db on the phone is the ONLY copy of the mined corpus + triage
// decisions, and Android deletes app data on uninstall -- which is how it was
// once wiped by an instrumented-test run. gradle.properties keeps the
// after-test uninstall from happening; this snapshots the DB immediately
// BEFORE every gradle-initiated wipe (instrumented-test runs and the
// uninstall* tasks) as the second layer of insurance.
// ---------------------------------------------------------------------------
val backupMiningDb by tasks.registering(Exec::class) {
    description = "Snapshot the phone's mining.db into <repo>/backups/"
    group = "verification"
    workingDir = rootProject.projectDir
    commandLine("python3", rootProject.file("scripts/backup_mining_db.py").absolutePath)
    // No isIgnoreExitValue: the script exits 0 when there is nothing to
    // protect (no device / app not installed / mining.db never created) and
    // non-zero only when a backup was due but FAILED -- that must block the
    // test run, otherwise the guard silently becomes the wipe it prevents.
}

// connectedDebugAndroidTest = the historical accident; uninstallAll /
// uninstallDebug / uninstallRelease / uninstallDebugAndroidTest all delete app
// data the same way. Backup failure must block any of them (escape hatch for a
// deliberate wipe-without-backup: `gradle <task> -x backupMiningDb`).
tasks.matching {
    it.name == "connectedDebugAndroidTest" || it.name.startsWith("uninstall")
}.configureEach {
    dependsOn(backupMiningDb)
}
