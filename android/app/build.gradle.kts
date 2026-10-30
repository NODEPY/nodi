plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "dev.nodi.android"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.nodi.android"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "1.0.0-alpha.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}

// Nodi: native QR scanner provided by Google Play services.
dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}

// Nodi: generate receiver QR locally.
dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
