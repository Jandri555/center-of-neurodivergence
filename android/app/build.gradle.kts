plugins {
    id("com.android.application")
}

android {
    namespace = "com.neurocenter.apps.agenda"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.neurocenter.agenda"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Reutiliza el mismo modelo y el mismo historial de operaciones que el escritorio.
    sourceSets {
        getByName("main") {
            java.srcDir("../../src/main/java")
            java.include(
                "com/neurocenter/apps/agenda/AgendaEntry.java",
                "com/neurocenter/apps/agenda/AgendaOperation.java",
                "com/neurocenter/apps/agenda/AgendaSyncJournal.java",
                "com/neurocenter/apps/agenda/AgendaSyncProtocol.java",
                "com/neurocenter/apps/agenda/AgendaStore.java",
                "com/neurocenter/apps/agenda/MainActivity.java"
            )
        }
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
}
