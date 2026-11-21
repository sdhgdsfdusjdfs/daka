plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "cn.daka.local"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "cn.daka.local"
        minSdk = 23
        targetSdk = 35
        versionCode = 13
        versionName = "0.2.3"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    signingConfigs {
        create("localRelease") {
            if (System.getenv("DAKA_KEYSTORE") != null) {
                storeFile = file(System.getenv("DAKA_KEYSTORE"))
                storePassword = System.getenv("DAKA_STORE_PASSWORD")
                keyAlias = "daka"
                keyPassword = System.getenv("DAKA_STORE_PASSWORD")
            }
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("localRelease") } }
    lint { abortOnError = true }
}
dependencies { testImplementation("junit:junit:4.13.2") }
