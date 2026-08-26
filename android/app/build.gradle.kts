plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "br.com.vitta.app"
  compileSdk = 34

  defaultConfig {
    applicationId = "br.com.vitta.app"
    minSdk = 24                 // Android 7.0 — cobre praticamente todo aparelho em uso
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      // Assinado com a chave de depuração: instala no seu aparelho sem
      // precisar de conta de desenvolvedor. Não serve para a Play Store.
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  kotlinOptions { jvmTarget = "17" }
  buildFeatures { buildConfig = true }
}

dependencies {
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.webkit:webkit:1.11.0")
  implementation("androidx.activity:activity-ktx:1.9.0")
  implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
}
