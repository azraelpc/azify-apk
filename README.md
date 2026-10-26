# AZify para Android

Cliente nativo generico para servidores [Azify](https://github.com/azraelpc/azify).
Android 8.0 o superior. No contiene servidores, cuentas ni credenciales
preconfigurados y no requiere modificar el servidor.

## Usar la app

El APK de desarrollo se genera en
[app/build/outputs/apk/debug/app-debug.apk](app/build/outputs/apk/debug/app-debug.apk).
Instalarlo en el telefono y abrir AZify. El campo **AZify Server URL** esta vacio
por defecto. Introducir la URL de un servidor propio, por ejemplo
`https://example.com/`, y pulsar Conectar. Se consulta primero `/api/music` sin
credenciales: si es publico, se entra directamente; solo una respuesta HTTP 401
abre el campo de contrasena. Las respuestas 403, los fallos de red y un catalogo
no valido no se confunden con una peticion de contrasena.

**Guardar contrasena** viene desmarcado. Sin marcarlo, el acceso solo se mantiene
en memoria mientras siga vivo el proceso de la app, incluyendo la reproduccion
en segundo plano y Android Auto. Cuando Android cierre ese proceso, habra que
autenticarse de nuevo. Si se marca, el hash se guarda cifrado con Android Keystore.
La URL se recuerda localmente; Cerrar sesion olvida tanto la URL como el acceso.
Desmarcar la opcion y conectar elimina cualquier credencial guardada anteriormente.

El servidor puede cambiarse desde Ajustes; solo se aceptan servidores HTTPS
en la raiz del dominio, sin rutas adicionales. Un servidor HTTP local necesita
un proxy o tunel HTTPS antes de poder utilizarse con este cliente.

El identificador generico es `org.azify.client`. No actualiza ni hereda datos de
versiones anteriores con otro identificador: se instala como una app independiente.

- Albums, carpetas anidadas, pistas y busqueda por titulo o carpeta.
- Cola compartida entre telefono, Android Auto, auriculares y controles del sistema.
- Reproducir/pausar, anterior/siguiente, seek, saltos de 10 segundos, shuffle y repetir.
- Continuidad entre albums y reanudacion de la ultima pista y posicion guardadas.
- Streaming en segundo plano, audio focus, pausa al desconectar auriculares y caratulas protegidas.

Es una interfaz Android nativa, no un WebView: no reproduce el portal 3D, el
visualizador ni el compresor Web Audio de la web. Necesita conexion a Internet;
no incluye descargas ni reproduccion offline. Los formatos dependen de los
decodificadores disponibles en Android/Media3 y del contenido servido.

## Android Auto

El servicio `MediaLibraryService` publica Albums, Carpetas y Reproducir todo.
Soporta busqueda, seleccion de pistas, cola, play/pause, next/previous y seek
mediante MediaSession. El coche decide que controles mostrar y puede limitar
la navegacion y el seek mientras se conduce. No se muestra la pagina web en el coche.

Para probar este APK instalado fuera de Google Play:

1. Abrir AZify e iniciar sesion en el telefono antes de conectar el coche.
2. En Ajustes de Android Auto, pulsar repetidamente en la version para activar
   sus opciones de desarrollador (normalmente 10 veces).
3. Desde el menu de desarrollador de **Android Auto**, activar **Fuentes
   desconocidas / Unknown sources**. No es el permiso general de instalar APK.
4. Reconectar el telefono al coche y buscar AZify en el selector de musica o en
   Personalizar lanzador.

La admision de apps instaladas por ADB puede variar segun Android Auto y el
fabricante. Si no aparece, la alternativa oficial es distribuir mediante una
pista de prueba interna de Google Play. El APK debug no implica aprobacion de
Google Play ni garantiza admision por todos los coches. La publicacion requiere
firma propia y cumplir las politicas de apps multimedia para coches.

## Crear el APK

En Windows, sin Java/SDK preinstalados:

```powershell
.\scripts\build.ps1 -AcceptSdkLicenses
```

El parametro acepta las licencias del SDK de Google; revisar primero
<https://developer.android.com/studio/terms>. Las herramientas se descargan
dentro de `.tools/`, sin instalacion global. En siguientes builds basta con
`.\scripts\build.ps1`. Ejecuta 35 pruebas locales, Android Lint y assembleDebug.
Las pruebas usan servidores simulados y dominios de ejemplo, nunca datos personales.

Con JDK 17 y Android SDK 35 ya configurados, o desde Android Studio:

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Abrir esta carpeta como proyecto Gradle en Android Studio. VS Code puede editar
el codigo y ejecutar los scripts; su analizador Java generico puede no importar
automaticamente el classpath de Android. Gradle es la referencia para compilar.
Las dependencias estan fijadas y el wrapper verifica el SHA-256 de Gradle 8.11.1.
El build release necesita una clave de firma propia; no publicar con la clave debug.

## Pruebas en telefono

Activar depuracion USB y autorizar el PC. Preparar los APK sin borrar datos:

```powershell
.\scripts\test-device.ps1 -InstallPackages
```

Aceptar ambas instalaciones en el telefono. Abrir AZify e iniciar sesion. Luego:

```powershell
.\scripts\test-device.ps1
```

Las tres pruebas comprueban biblioteca, carpetas, busqueda, caratulas autenticadas,
streaming, pausa, seek, siguiente y anterior, con clientes Media3 y
MediaBrowserCompat (protocolo de compatibilidad usado por Android Auto).
Requieren una biblioteca con al menos un album reproducible; silencian el audio
de AZify durante la prueba y dejan la reproduccion pausada al terminar.
`-Serial ID` permite elegir un dispositivo.

Evitar `connectedDebugAndroidTest` sobre una instalacion personal con datos:
el ejecutor de Gradle puede desinstalar la app al limpiar una sesion fallida.
El script anterior no desinstala paquetes. Las pruebas del protocolo no sustituyen
una prueba de la interfaz en el coche o con Desktop Head Unit.

## Datos y seguridad

La clave se convierte al mismo SHA-256 usado por el servidor Azify. Ese hash
equivale a una credencial de acceso: solo se guarda si el usuario elige Guardar
contrasena, cifrado con AES-GCM y Android Keystore. Si no, permanece exclusivamente
en memoria. Nunca forma parte del APK, las URL ni los logs. Se excluyen los datos
de backups y transferencias. El acceso guardado desaparece al cerrar sesion o desinstalar.
Si la clave cambia en el servidor, introducirla de nuevo en Ajustes.

API, audio e imagenes usan HTTPS y, solo si es necesario, `X-Azify-Pass`.
Se rechazan redirecciones y recursos de otros origenes para no reenviar
la credencial a otro servidor. Las caratulas llegan a los clientes
multimedia autorizados mediante un ContentProvider de solo lectura.
El servicio acepta la propia app y controladores multimedia de confianza del
sistema; las operaciones de cambiar acceso y recargar solo se aceptan desde AZify.
El catalogo se mantiene en memoria un minuto; Actualizar fuerza su recarga.

Para compartir el proyecto, no incluir `.tools/`, `.gradle/`, los directorios
`build/`, `local.properties` ni claves de firma propias. Estan excluidos en
`.gitignore`; son herramientas, caches, informes o datos locales, no fuentes.
El enlace al repositorio publico de Azify documenta el protocolo compatible;
no es un servidor predeterminado ni una cuenta incluida en el cliente.