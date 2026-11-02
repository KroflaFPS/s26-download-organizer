import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.kroflafps.s26downloadorganizer"
    compileSdk {
        version = release(37)
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.kroflafps.s26downloadorganizer"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
}

val sourceManifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")
tasks.withType<Test>().configureEach {
    inputs.file(sourceManifest).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("s26.sourceManifest", sourceManifest.asFile.absolutePath)
}
