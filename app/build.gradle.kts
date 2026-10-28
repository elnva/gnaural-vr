plugins {
    id("com.android.application")
}

val spatialSdk = "0.14.0"

android {
    namespace = "vr.gnaural"
    compileSdk = 36

    defaultConfig {
        applicationId = "vr.gnaural"
        // Horizon OS is Android 14 (API 34), as in Meta's StarterSample.
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    packaging { resources.excludes.add("META-INF/LICENSE") }
    lint { abortOnError = false }
}

dependencies {
    implementation("com.meta.spatial:meta-spatial-sdk:$spatialSdk")
    implementation("com.meta.spatial:meta-spatial-sdk-toolkit:$spatialSdk")
    implementation("com.meta.spatial:meta-spatial-sdk-vr:$spatialSdk")
    testImplementation("junit:junit:4.13.2")
}
