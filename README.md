# VERTIL

**Entorno local inteligente para Android.**

VERTIL no es un chatbot. Es un entorno que integra un modelo de lenguaje local reemplazable con herramientas controladas del sistema Android, gestionadas por un Core estable con políticas de seguridad reales.

## Versión

`1.0.0` (versionCode `1`)

## Principio arquitectónico

```
VERTIL APP → UI → VERTIL CORE → [MODELS | TOOLS | PERMISSION ENGINE] → ANDROID
```

**El modelo es reemplazable. El Core permanece.**

El modelo NUNCA tiene acceso directo a APIs sensibles. Solo puede solicitar acciones mediante herramientas definidas por VERTIL CORE, sujetas a políticas de permisos.

## Identidad

- **Nombre:** VERTIL
- **Desarrollador:** Vertil Jivenson
- **Identidad:** pertenece al Core, no al modelo subyacente.

## Características v1.0

### Core
- VertilCore facade central
- Sistema de identidad propio (VertilIdentity)
- System Prompt centralizado (no es el mecanismo de seguridad primario)
- Configuración versionada (Core 1.0.0, Policy 1.0.0, Tool API 1.0)
- Logging centralizado (VertilLog)
- Error Manager (sin catchs vacíos)

### Modelo reemplazable
- Interfaz `LocalModel` común: load/unload/generate/cancel/getInfo/getCapabilities
- Registry de runtimes:
  - **ONNX Runtime Android** — IMPLEMENTADO (inferencia real con cualquier .onnx)
  - **GGUF** — NO IMPLEMENTADO (stub documentado, requiere NDK + JNI llama.cpp, v1.1)
  - **TFLite** — NO IMPLEMENTADO (stub documentado, v1.1)
  - **Mock** — IMPLEMENTADO (runtime sin modelo, respuestas claras)
- ModelManager: importar, eliminar, activar, cargar, descargar, verificar hash SHA-256

### Permission Engine
- 5 niveles: READ(0), ORGANIZE(1), MODIFY(2), AUTOMATE(3), SENSITIVE(4)
- SENSITIVE siempre requiere confirmación explícita
- Política configurable (auto-confirm por nivel, capacidades deshabilitables)
- Sesión con capacidades confirmadas persistidas
- Pending requests para UI

### Tools (9 herramientas iniciales)
- FileSearchTool, FileListTool, FileMoveTool, FileCopyTool, FileRenameTool
- FolderCreateTool, FileHashTool
- DeviceInfoTool, ModelInfoTool, TaskTool
- Cada tool declara capabilities → el Permission Engine decide
- Resultados estructurados: ToolResult.Success / ToolResult.Error

### Storage
- FileEngine basado en DocumentFile/SAF + internal storage
- No asume acceso absoluto al filesystem
- Compatible con Android 8.0+ (API 26+)

### Device Profiler
- RAM, CPU, ABI, almacenamiento, Android version
- Compatibility Assessor: evalúa si un modelo cabe en memoria
- NO afirma velocidades no medidas

### Automation
- Modelo de reglas (Trigger + Action)
- Persistencia Room
- Validación de reglas
- UI de creación/edición
- Ejecución real: v1.1 (requiere WorkManager scheduler)

### Activity Log
- Registro persistente de toda acción de tools
- Timestamp, operación, resultado, source/destination
- UI con historial

### Chat
- Sesión con mensajes (user/assistant/system)
- Estado observable (isGenerating, error, activeModel)
- Métricas: tokens, duración, tok/s
- Cancelación de generación
- Saludo inicial de VERTIL (no se repite)

### UI (Jetpack Compose)
- 8 pantallas: Home, Chat, Models, Files, Activity, Automations, Permissions, Settings
- Tema oscuro premium (cian/violeta)
- Splash screen (AndroidX core-splashscreen)
- Icono adaptable
- Navegación por NavigationBar
- Estados vacíos diseñados

### Persistencia (Room)
- models, activity_log, chat_messages, automations, granted_folders
- DataStore para preferencias

## Stack técnico

- Kotlin 1.9.24, AGP 8.5.2, Gradle 8.9
- Jetpack Compose (BOM 2024.08.00), Material 3
- ONNX Runtime Android 1.18.0
- Room 2.6.1, DataStore 1.1.1
- AndroidX core-splashscreen 1.0.1
- DocumentFile 1.0.1, WorkManager 2.9.1
- minSdk 26 / targetSdk 34 / compileSdk 34
- **Sin permiso INTERNET** (offline-first)

## Compilar

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

## Tests

| Suite | Tests |
|---|---|
| IdentityAndPromptTest | 7 |
| PermissionManagerTest | 8 |
| AutomationValidatorTest | 5 |
| ModelFormatTest | 5 |
| ModelInfoTest | 1 |
| **Total** | **25** (todos PASS) |

## Limitaciones conocidas

1. **GGUF/TFLite no implementados**: los stubs están documentados. v1.1 los integrará via NDK.
2. **Tokenizador LLM**: el runtime ONNX usa tokenización whitespace simple. Para LLM reales (Llama, Gemma) se requiere BPE/SentencePiece (v1.1).
3. **Automatización**: las reglas se crean y persisten, pero la ejecución automática está pospuesta a v1.1.
4. **No probado en dispositivo**: no se validó inferencia real ni UI en device/emulador.
5. **ACCESS_NETWORK_STATE**: añadido automáticamente por dependencias; no permite acceso a red.

## Licencia

Uso interno — VERTIL · Vertil Jivenson.
