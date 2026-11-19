# Aplicaciones NeuroCenter .ncapp

El formato .ncapp es un JAR de Java 21 con el manifiesto de NeuroCenter.
El mismo paquete contiene la lógica compartida, una interfaz Swing para escritorio
y una clase móvil Java 21 que devuelve JSON declarativo.

## Manifiesto

~~~text
Manifest-Version: 1.0
NeuroCenter-App-Id: com.neurocenter.demo
NeuroCenter-App-Desktop: true
NeuroCenter-App-Mobile: true
NeuroCenter-Mobile-Class: com.neurocenter.demo.DemoMobile
Implementation-Title: Ejemplo Java 21
~~~

En escritorio, META-INF/services/com.neurocenter.api.NeuroApp
debe contener el nombre de la clase implementadora de NeuroApp.

## Interfaz Android Java 21

El constructor público sin argumentos de NeuroCenter-Mobile-Class debe
implementar dos métodos públicos:

~~~java
String renderJson();
String onAction(String actionId);
~~~

Ambos devuelven un JSON declarativo, por ejemplo:

~~~json
{"title":"Demo","text":"Hola desde Java 21","actions":[{"id":"sumar","label":"Sumar uno"}]}
~~~

El host dibuja el título, texto y hasta 12 botones con controles Android.
Cada botón invoca onAction(id) y reemplaza la pantalla con el JSON devuelto.
No se ejecuta código de interfaz dentro del proceso principal de NeuroCenter.

El ejemplo completo está en examples/ncapp-demo y es un único archivo para
ambas plataformas; Maven CI publica neurocenter-example-ncapp.

## Compilación

Con JDK 21 y el proyecto principal compilado (mvn clean package):

~~~sh
mkdir -p target/ncapp-example-classes
javac --release 21 -cp target/center-of-neurodivergence.jar -d target/ncapp-example-classes examples/ncapp-demo/src/main/java/com/neurocenter/demo/*.java
jar --create --file target/neurocenter-demo.ncapp --manifest examples/ncapp-demo/MANIFEST.MF -C target/ncapp-example-classes . -C examples/ncapp-demo META-INF
~~~

En Windows, el instalador EXE asocia .ncapp con NeuroCenter. Hacer doble
clic muestra la confirmación de instalación, incluso si la ventana ya estaba abierta.
En Android se puede instalar desde el gestor de archivos o desde el menú de aplicaciones.

## Seguridad y estado

El motor Android intenta lanzar la clase móvil en un UID realmente aislado
utilizando descriptores Binder de solo lectura. No incluye aún el puente
de operaciones privilegiadas (Agenda, credenciales, red y documentos):
almacenar una concesión de permiso no significa que esas APIs estén disponibles.

El arranque de HotSpot 21 desde un proceso ART aislado y el uso de descriptores
/proc/self/fd están implementados, pero requieren prueba en dispositivo
y pruebas de ataques. Si falla, NO se debe desactivar el aislamiento ni cargar
el Java externo en la aplicación principal para hacerlo funcionar.

Android 10 es el mínimo (API 29); actualmente solo ARM64.

## Permisos y servicios del host (API experimental)

Una interfaz puede devolver una petición en lugar de una pantalla:

~~~json
{"hostRequest":{"capability":"agenda.read","operation":"list"}}
~~~

El proceso principal de NeuroCenter verifica el permiso actual de ese
paquete (identificador y SHA-256) y ejecuta la operación si está concedida.
Después la app Java 21 recibe:

~~~java
String onHostResponse(String capability, String responseJson);
~~~

La respuesta contiene ok:true y los datos, o bien ok:false y error.
Se admiten de momento agenda.read/list y agenda.write/addTask
(campos title, date y time ISO opcionales y notes opcional).
Revocar el permiso en el gestor bloquea las siguientes operaciones.
Las demás capacidades todavía no tienen puente móvil. El proceso
aislado no puede acceder a AgendaStore por su cuenta.
