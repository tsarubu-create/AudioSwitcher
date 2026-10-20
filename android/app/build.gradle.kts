import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release 版の署名の鍵。置き場所と合言葉は android/keystore.properties（git に入れない）に書く（docs/開発環境.md）。
// 無ければ、Release 版は署名せずに作る（端末には入れられない）。
val releaseSigning = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
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

    signingConfigs {
        if (releaseSigning != null) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // ふだん使うのは Release 版（Debug 版は run-as でアプリの中に入れるため。docs/構成.md の 2.6）。
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
        // Debug 版は別のアプリとして入れ、Release 版と並べて置けるようにする。
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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
