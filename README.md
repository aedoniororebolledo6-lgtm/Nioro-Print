# Nioro Print

App Android **nativa** (Kotlin, sin HTML) para controlar impresoras térmicas ESC/POS de 58, 72 y 80 mm, como las "Bill Ticket Printer" de AliExpress.

## Instalar

Descarga siempre la última versión:

**https://github.com/aedoniororebolledo6-lgtm/Nioro-Print/releases/latest/download/Nioro-Print.apk**

(Abre el enlace en el celular, descarga el APK y permite "instalar apps desconocidas" cuando Android lo pida.)

## Qué hace

- **Conexión forzada**: Bluetooth clásico (SPP seguro e inseguro, canales RFCOMM 1-3 por reflexión, UUID del dispositivo), Bluetooth BLE (GATT, detecta la característica de escritura y el MTU), USB OTG y WiFi (RAW 9100). Con reintentos y reconexión automática si la conexión cae.
- **Imprimir**: texto (alineación, negrita, tamaño 1x-4x), imágenes de la galería (dithering), QR, código de barras Code 128, página de prueba, avanzar papel, cortar.
- **Ajustes de la impresora**: ancho de papel, oscuridad (ESC 7 / DC2 #), velocidad de envío, codificación (CP850, CP437, WPC1252, ASCII), texto como imagen, avance y corte, reintentos, reconexión al abrir.
- **Avanzado**: envío de bytes ESC/POS en hexadecimal, autotests de fábrica, consulta de estado/papel/modelo, registro copiable.
- **Actualización** desde GitHub Releases en Ajustes.

## Primera conexión (impresoras tipo 58 mm)

1. Enciende la impresora (luz fija).
2. En el celular: Conectar → Buscar impresoras Bluetooth. Si no está emparejada, toca "Emparejar primero" (PIN 0000 o 1234).
3. Toca Conectar y luego "Página de prueba" en la pestaña Imprimir.

Si salen símbolos raros en vez de acentos, cambia la codificación en Ajustes o activa "Texto como imagen".

## Compilación

GitHub Actions compila y publica un APK firmado en cada push a `main` (`.github/workflows/build.yml`).
Localmente: `./gradlew assembleRelease` (necesita Android SDK 34 y JDK 17).

> La llave de firma (`app/nioro-print.keystore`) está en el repositorio para que las actualizaciones se instalen encima de la versión anterior. Es válida para instalar por APK, no para Google Play.
