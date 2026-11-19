plugins {
    id("com.android.library")
}

android {
    namespace = "com.neurocenter.api"

    buildFeatures {
        aidl = true
    }
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // Contrato neutral que comparten las implementaciones desktop y mobile.
            java.srcDir("../../src/main/java/com/neurocenter/api")
        }
    }
}

// AppContext y NeuroApp pertenecen exclusivamente al host Swing.
// El AAR móvil solo exporta NeuroAppContext y NeuroPlatform.
tasks.withType<JavaCompile>().configureEach {
    exclude("**/AppContext.java", "**/NeuroApp.java", "**/plugin/**")
}
