import java.net.URL

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.udi.flickkick"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.udi.flickkick"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // חתימת debug כדי שאפשר יהיה להתקין את ה-release ישירות על הטלפון
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // MediaPipe – זיהוי 21 נקודות ציון של כף היד
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
}

// מוריד אוטומטית את מודל זיהוי היד לתיקיית assets בבנייה הראשונה
val handModelUrl =
    "https://storage.googleapis.com/mediapipe-models/hand_landmarker/hand_landmarker/float16/latest/hand_landmarker.task"

val downloadHandModel by tasks.registering {
    val out = layout.projectDirectory.file("src/main/assets/hand_landmarker.task")
    outputs.file(out)
    doLast {
        val f = out.asFile
        if (!f.exists() || f.length() < 1000) {
            f.parentFile.mkdirs()
            URL(handModelUrl).openStream().use { input ->
                f.outputStream().use { output -> input.copyTo(output) }
            }
            println("Downloaded hand_landmarker.task (${f.length()} bytes)")
        }
    }
}

tasks.named("preBuild") { dependsOn(downloadHandModel) }
