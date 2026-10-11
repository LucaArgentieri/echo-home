plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.echohome.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.echohome.app"
        minSdk = 26
        targetSdk = 30
        versionCode = 1
        versionName = "0.2.0"

        // Echo Show 5 is ARM; keeps the sherpa-onnx native libs small (no x86).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // Model files are read by the native loader straight from the APK; do not compress them.
    androidResources { noCompress += listOf("onnx", "txt") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.media3:media3-session:1.11.1")

    // sherpa-onnx is not on Maven Central: release AAR from github.com/k2-fsa/sherpa-onnx (see README).
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))

    testImplementation("junit:junit:4.13.2")
}
