plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ultron.assistant"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.ultron.assistant"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1-voice-clone"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("com.xdcobra.sherpa:sherpa-onnx:1.13.2-1")
    implementation("org.apache.commons:commons-compress:1.28.0")
}
