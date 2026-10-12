plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode naik otomatis di setiap build GitHub Actions (nomor run), jadi APK baru selalu bisa
// dipasang di atas yang lama tanpa uninstall.
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.xcam.camera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.xcam.camera"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    // Keystore tetap (app/xcam.jks): tanda tangan APK sama di setiap build, syarat agar bisa di-update.
    signingConfigs {
        create("xcam") {
            storeFile = file("xcam.jks")
            storePassword = "xcamera"
            keyAlias = "xcam"
            keyPassword = "xcamera"
        }
    }

    buildTypes {
        debug {
            // Gunakan debug signing bawaan Android.
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("xcam")
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
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")

    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-video:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}
