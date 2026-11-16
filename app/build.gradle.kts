plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quaytest.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.quaytest.app"
        minSdk = 29          // Android 10+: lưu video qua MediaStore không cần quyền bộ nhớ
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Bản chạy thật: tối ưu + thu gọn code (nhanh và nhẹ hơn hẳn bản debug)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Ký bằng khoá debug để cài đè lên bản đang dùng (cài nội bộ, không lên Play Store)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-effects:$camerax")

    // OCR offline (model đóng gói trong APK, không cần mạng)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Quét mã vạch / QR cho "Mã mạch" (UI quét của Google Play Services, không cần quyền camera riêng)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Tải video lên Google Drive chạy nền, tự thử lại khi mất mạng
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
