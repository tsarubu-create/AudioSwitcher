plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.tsarubucreate.audioswitcher"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.tsarubucreate.audioswitcher"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-試作"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.session)

    testImplementation(libs.junit)
}
