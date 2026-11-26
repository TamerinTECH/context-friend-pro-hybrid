import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}
val localProperties = Properties().apply { rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
android {
    namespace = "tech.tamerin.contextfriendpro"
    compileSdk = 36
    ndkVersion = "27.1.12297006"
    defaultConfig {
        applicationId = "com.tamerintech.contextfriendpro"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Firebase options come from untracked local.properties, never from a committed google-services.json.
        // Without them the app builds and runs fully offline; the hybrid features show "not configured".
        fun firebase(key: String) = "\"" + (localProperties.getProperty("firebase.$key") ?: "") + "\""
        buildConfigField("String", "FIREBASE_API_KEY", firebase("apiKey"))
        buildConfigField("String", "FIREBASE_APP_ID", firebase("appId"))
        buildConfigField("String", "FIREBASE_PROJECT_ID", firebase("projectId"))
        buildConfigField("String", "FIREBASE_SENDER_ID", firebase("senderId"))
        buildConfigField("String", "VERTEX_LOCATION", "\"" + (localProperties.getProperty("firebase.vertexLocation") ?: "eu") + "\"")
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { cppFlags += "-std=c++17"; arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
    }
    externalNativeBuild { cmake { path = file("../../native/CMakeLists.txt"); version = "3.22.1" } }
    sourceSets["main"].assets.srcDir("../../shared")
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Play upload key: only on a machine whose untracked local.properties names it. Elsewhere release stays unsigned.
    val upload = localProperties.getProperty("upload.storeFile")?.let { path -> signingConfigs.create("upload") {
        storeFile = file(path); storePassword = localProperties.getProperty("upload.storePassword")
        keyAlias = localProperties.getProperty("upload.keyAlias"); keyPassword = localProperties.getProperty("upload.storePassword")
    } }
    buildTypes {
        release { isMinifyEnabled = false; if (upload != null) signingConfig = upload }
        // Speech, speakers and Gemma run in native code: unoptimised (-O0) they are several times slower than real time,
        // so debug builds compile the engines optimised too. Kotlin stays debuggable.
        debug { externalNativeBuild { cmake { arguments += "-DCMAKE_BUILD_TYPE=Release" } } }
    }
    packaging { jniLibs { useLegacyPackaging = false }; resources.excludes += "META-INF/AL2.0"; resources.excludes += "META-INF/LGPL2.1" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.goterl:lazysodium-android:5.2.0@aar")
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    implementation("com.google.zxing:core:3.5.3")
    implementation(files("../../.deps/sherpa-onnx.aar"))
    // Firebase AI Logic hybrid inference. The on-device SDK is not in the BoM yet (docs: hybrid/android/get-started).
    implementation(platform("com.google.firebase:firebase-bom:35.2.0"))
    implementation("com.google.firebase:firebase-ai")
    implementation("com.google.firebase:firebase-ai-ondevice:16.0.0-beta08")
    implementation("com.google.firebase:firebase-appcheck-playintegrity")
    debugImplementation("com.google.firebase:firebase-appcheck-debug")
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-config")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
