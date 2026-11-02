import org.gradle.api.tasks.testing.Test
import com.android.build.api.artifact.SingleArtifact

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
        versionCode = 2
        versionName = "0.2.0"
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
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
    val mergedManifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
    tasks.withType<Test>().configureEach {
        inputs.file(mergedManifest).withPathSensitivity(PathSensitivity.RELATIVE)
        doFirst {
            systemProperty("s26.mergedManifest", mergedManifest.get().asFile.absolutePath)
        }
    }
}

val sourceManifest = layout.projectDirectory.file("src/main/AndroidManifest.xml")
tasks.withType<Test>().configureEach {
    inputs.file(sourceManifest).withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("s26.sourceManifest", sourceManifest.asFile.absolutePath)
}
