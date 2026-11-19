plugins {
    id("com.android.application")
}

android {
    // Paquete raíz de la aplicación Android. Las pantallas siguen organizadas
    // en subpaquetes funcionales, por ejemplo com.neurocenter.apps.agenda.
    namespace = "com.neurocenter"
    compileSdk = 36

    // Clave de depuración estable para que los APK de CI puedan actualizarse
    // entre ejecuciones. Es una clave DEBUG pública; nunca usarla para releases.
    signingConfigs {
        create("stableDebug") {
            storeFile = rootProject.file("agenda-debug.keystore")
            storePassword = "android"
            keyAlias = "agenda-debug"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.neurocenter"
        minSdk = 29
        targetSdk = 36
        // El JRE de OpenJDK 21 incluido actualmente es para ARM64.
        ndk.abiFilters += "arm64-v8a"
        versionCode = 8
        versionName = "1.6.1"
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("stableDebug")
        }
    }

    // La aplicación anfitriona sigue compilando sus pantallas Android con Java 17.
    // El JRE ARM64 separado ejecuta bytecode Java 21 dentro de una JVM HotSpot.
    ndkVersion = "27.2.12479018"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
    packaging {
        jniLibs {
            // OpenJDK usa bibliotecas reales en nativeLibraryDir, no ejecutables
            // extraídos a una carpeta de datos editable (Android 10 W^X).
            useLegacyPackaging = true
            keepDebugSymbols += "**/libjvm.so"
            keepDebugSymbols += "**/libjava.so"
            keepDebugSymbols += "**/libjli.so"
        }
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
            assets.srcDir(layout.buildDirectory.dir("generated/java21/assets").get().asFile)
            jniLibs.srcDir(layout.buildDirectory.dir("generated/java21/jniLibs").get().asFile)
        }
    }
}

// El build SIEMPRE descarga y verifica un release JRE21 fijo. Nada de runtimes
// instalados por separado en el teléfono: las bibliotecas y el módulo Java se
// empaquetan en el propio APK ARM64.
// En Windows aceptamos el lanzador Python estándar `py -3`; en Linux python3.
val prepareJava21Runtime by tasks.registering(Exec::class) {
    val script = rootProject.file("scripts/prepare_java21_runtime.py")
    val python = if (System.getProperty("os.name").lowercase().contains("win")) {
        listOf("py", "-3")
    } else {
        listOf("python3")
    }
    commandLine(python + listOf(script.absolutePath, layout.buildDirectory.dir("generated/java21").get().asFile.absolutePath))
    workingDir(rootProject.projectDir)
}
tasks.named("preBuild") { dependsOn(prepareJava21Runtime) }

// AGP 9.4 expone AndroidSourceDirectorySet sin PatternFilterable.
// Excluimos los archivos Swing a nivel de tarea de compilación Java.
tasks.withType<JavaCompile>().configureEach {
    exclude(
        "**/AgendaApp.java",
        "**/AgendaPanel.java",
        "**/BluetoothSerialClient.java",
        "**/UsbAoaClient.java",
    )
}

dependencies {
    implementation(project(":neurocenter-api"))
    implementation("com.google.code.gson:gson:2.11.0")
}
