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

    // El motor compartido vive en el proyecto de escritorio. Se añade solo
    // su carpeta y se excluyen las dos clases de interfaz Swing.
    sourceSets {
        getByName("main") {
            java.directories.add("../../src/main/java/com/neurocenter/apps/agenda")
        }
    }
}

// AGP 9.4 expone AndroidSourceDirectorySet sin PatternFilterable.
// Excluimos los archivos Swing a nivel de tarea de compilación Java.
tasks.withType<JavaCompile>().configureEach {
    exclude("**/AgendaApp.java", "**/AgendaPanel.java")
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
}
