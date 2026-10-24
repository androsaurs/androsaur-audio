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
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(project(":shared"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.material3:material3:1.3.1")
}
