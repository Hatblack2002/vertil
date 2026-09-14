# Changelog

## [1.0.0] - 2026-09-14

### Added
- VERTIL CORE: facade central con identidad, system prompt, configuración versionada
- Modelo reemplazable: interfaz LocalModel + ModelManager + registry
  - ONNX Runtime Android (real, inferencia funcional con cualquier .onnx)
  - Mock runtime (claramente marcado, para uso sin modelo)
  - GGUF y TFLite: stubs documentados (v1.1)
- Permission Engine: 5 niveles, política configurable, confirmación de usuario
- 9 tools iniciales: file_search, file_list, file_move, file_copy, file_rename, folder_create, file_hash, device_info, model_info, task_list
- FileEngine basado en SAF/DocumentFile
- DeviceProfiler + CompatibilityAssessor
- Automation: modelo de reglas + persistencia + validación (ejecución v1.1)
- ActivityLog: registro persistente de acciones
- Chat: sesión, estado, métricas, cancelación
- UI Compose: 8 pantallas + splash + tema oscuro premium
- Room: 5 entidades (models, activity, chat, automations, granted_folders)
- 25 tests unitarios (identity, permissions, automation, models)
- Auto-check script (100 verificaciones PASS)

### Security
- Sin permiso INTERNET
- Modelo no puede modificar políticas, permisos, ni system prompt
- Todas las acciones pasan por ToolManager → PermissionManager

### Known Limitations
- GGUF/TFLite no implementados (stubs documentados)
- Tokenizador LLM simple (v1.1: BPE/SentencePiece)
- Ejecución de automatizaciones pospuesta a v1.1
- No probado en dispositivo físico
