plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
    namespace = "dev.hram.androsaur.audio"
    compileSdk = 35
    defaultConfig {
        applicationId = "dev.hram.androsaur.audio"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-skeleton"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // The sherpa-onnx native code calls Kotlin lambdas by the signature of a lambda compiled to a class, not through invokedynamic.
    kotlinOptions { jvmTarget = "17"; freeCompilerArgs += "-Xlambdas=class" }
}
dependencies {
    implementation(project(":shared"))
    implementation(project(":microwakeword"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("com.alphacephei:vosk-android:0.3.75")
    // Piper speech; scripts/fetch-tts.sh downloads the library into libs/.
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
}
