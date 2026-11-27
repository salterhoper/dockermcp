import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val keystoreProperties = Properties().apply {
  val propsFile = rootProject.file("keystore.properties")
  if (propsFile.exists()) {
    propsFile.inputStream().use { load(it) }
  }
}

val releaseStoreFilePath: String? = System.getenv("SIGNING_STORE_FILE")
  ?: keystoreProperties.getProperty("storeFile")
val releaseStorePassword: String? = System.getenv("SIGNING_STORE_PASSWORD")
  ?: keystoreProperties.getProperty("storePassword")
val releaseKeyAlias: String? = System.getenv("SIGNING_KEY_ALIAS")
  ?: keystoreProperties.getProperty("keyAlias")
val releaseKeyPassword: String? = System.getenv("SIGNING_KEY_PASSWORD")
  ?: keystoreProperties.getProperty("keyPassword")
val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
  !releaseStorePassword.isNullOrBlank() &&
  !releaseKeyAlias.isNullOrBlank() &&
  !releaseKeyPassword.isNullOrBlank()

android {
  namespace = "tech.codesky.dockdeploy"
  compileSdk = 36
  defaultConfig {
    applicationId = "tech.codesky.dockdeploy"
    minSdk = 26
    targetSdk = 36
    versionCode = 1
    versionName = "1.0.0"
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = rootProject.file(releaseStoreFilePath!!)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      if (hasReleaseSigning) {
        signingConfig = signingConfigs.getByName("release")
      }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.datastore.preferences)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose & Official Material Icons + Adaptive
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material3.adaptive)
  implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
  implementation(libs.androidx.compose.material3.window.size)
  implementation(libs.androidx.compose.material.icons.extended)

  // Networking, Streaming & Serialization
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.kotlinx.serialization.json)

  // 2FA QR Code Rendering
  implementation(libs.zxing.core)

  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation 3
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)
}
