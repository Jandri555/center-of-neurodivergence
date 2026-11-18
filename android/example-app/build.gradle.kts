// Ejemplo de app externa para Center of Neurodivergence Android.
// Genera un APK con: gradle -p android :example-app:assembleDebug
// La API del host es compileOnly: NO se empaqueta, la aporta el panel en tiempo de ejecución.
plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.hello"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.hello"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly(project(":neurocenter-api"))
}
