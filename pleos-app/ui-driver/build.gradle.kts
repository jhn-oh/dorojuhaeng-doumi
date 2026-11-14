plugins { id("com.android.application") }
android {
    namespace = "kr.juhyu.licensenote.driver"
    compileSdk = 35
    defaultConfig { applicationId = "kr.juhyu.licensenote.driver"; minSdk = 28; targetSdk = 35; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
