plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.example.otginterval"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.example.otginterval"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5-touch-lock"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("com.github.ernestp.AndroidUSBCamera:libausbc:3.6.0")
}
configurations.configureEach {
    resolutionStrategy.force(
        "androidx.core:core:1.16.0", "androidx.core:core-ktx:1.16.0"
    )
}
