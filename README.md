# Screen Recorder Gamez

Aplicación para Android desarrollada con Flutter y Kotlin nativo para la grabación de pantalla con captura y mezcla simultánea de audio interno del sistema y micrófono externo.

---

## Características Principales

- **Captura de Pantalla en Alta Definición**:
  - Selector de resolución: 1080p (Full HD) y 720p (HD).
  - Tasa de cuadros por segundo configurable: 30 FPS y 60 FPS.
  - Tasa de bits de video ajustable: 4 Mbps, 6 Mbps, 8 Mbps y 12 Mbps.
  - Inyección continua de cuadros (`KEY_REPEAT_PREVIOUS_FRAME_AFTER`) para prevenir congelamientos en interfaces estáticas.

- **Motor de Audio Dual en Tiempo Real**:
  - **Audio Interno**: Captura directa del sonido del sistema, juegos y aplicaciones mediante `AudioPlaybackCaptureConfiguration` (Android 10+).
  - **Micrófono Externo**: Captura de voz y narración mediante `AudioRecord` en canal mono centrado.
  - **Mezclador PCM Concurrente**: Hilos de captura desacoplados con colas concurrentes y buffer Little-Endian, eliminando desincronizaciones y distorsión acústica.
  - Codificación estéreo AAC a 128 kbps (44.1 kHz).

- **Servicio en Primer Plano Persistente**:
  - `Foreground Service` con tipo `mediaProjection` para continuidad de grabación con la app minimizada.
  - Notificación persistente con control de parada integrado.

- **Gestor de Grabaciones Integrado**:
  - Modal interactiva al finalizar la sesión con duración, tamaño y acceso directo a reproducción.
  - Vista de historial con auto-recarga reactiva sin necesidad de refresco manual.
  - Reproducción directa de videos y eliminación de archivos.

---

## Arquitectura Técnica

- **Framework UI**: Flutter (Dart 3.7+ / Flutter 3.29+)
- **Capa Nativa**: Kotlin (JVM 17, Android Gradle Plugin 8.7, NDK 27.0.12077973)
- **APIs de Android**:
  - `android.media.projection.MediaProjection`
  - `android.media.AudioPlaybackCaptureConfiguration`
  - `android.media.MediaCodec` (H.264 / AVC & AAC-LC)
  - `android.media.MediaMuxer` (Contenedor MP4)
- **Requisitos de Sistema**:
  - Versión mínima: Android 10 (API 29).
  - Versión objetivo: Android 14 (API 34).

---

## Estructura del Proyecto

```
lib/
├── main.dart                  # Punto de entrada, configuración de tema y navegación
├── models/                    # Modelos de datos
├── screens/
│   ├── home_screen.dart       # Controles de grabación, selectores y modal de resumen
│   └── recordings_screen.dart # Listado reactivo, reproductor y borrado
└── services/
    └── recorder_service.dart  # Puente MethodChannel y EventChannel con Android

android/app/src/main/kotlin/com/screenrecorder/app/screen_recorder_gamez/
├── MainActivity.kt            # Manejo de intents de proyección y canales de plataforma
├── RecordService.kt           # Foreground Service y notificación del sistema
└── ScreenRecorder.kt          # Pipeline nativo de video, mezcla PCM y multiplexación MP4
```

---

## Compilación e Instalación para Producción (Modo Release)

El modo **Release** compila la aplicación en binario AOT (Ahead-Of-Time) optimizado, eliminando la sobrecarga del depurador, la bandera de depuración y maximizando el rendimiento general (equivalente a una app descargada desde Play Store).

### 1. Compilar e Instalar Directamente en el Teléfono
Si el dispositivo está conectado por USB (o Wi-Fi debugging), este comando compila en modo Release y lo instala de inmediato en el teléfono:

```bash
flutter run --release
```

### 2. Solo Generar el Archivo APK (Sin Instalar)
Para generar únicamente el archivo empaquetado `.apk` instalable:

```bash
flutter build apk --release
```

El instalador final se generará en:

```
build/app/outputs/flutter-apk/app-release.apk
```

### 3. Instalar un APK Release Ya Compilado
Si el APK ya fue generado previamente y se desea transferir al teléfono conectado:

Con Flutter:
```bash
flutter install --release
```

O directamente mediante ADB:
```bash
adb install -r build/app/outputs/flutter-apk/app-release.apk
```

---

## Autor

Desarrollado por **Rafael Gamez Diaz**.
