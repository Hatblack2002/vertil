#!/usr/bin/env python3
"""VERTIL — Auto-check del proyecto."""
import os, re, sys

PROJ = "/home/z/my-project/vertil"
APP = os.path.join(PROJ, "app")
SRC = os.path.join(APP, "src", "main", "java", "com", "vertil")
TEST = os.path.join(APP, "src", "test", "java", "com", "vertil")

EXPECTED_DIRS = [
    "core", "core/identity", "core/prompt", "core/config", "core/log", "core/error",
    "model", "model/runtime",
    "permissions", "tools", "tools/impl",
    "storage", "automation", "device", "activity", "chat",
    "persistence", "persistence/entities", "persistence/daos",
    "background", "di",
    "ui", "ui/theme", "ui/components", "ui/screens",
]

passed = 0; failed = 0; warnings = 0

def ok(m):
    global passed; passed += 1; print(f"  [PASS]  {m}")
def fail(m):
    global failed; failed += 1; print(f"  [FAIL]  {m}")
def warn(m):
    global warnings; warnings += 1; print(f"  [WARN]  {m}")
def section(t): print(f"\n=== {t} ===")

section("1. Estructura de directorios")
for d in EXPECTED_DIRS:
    full = os.path.join(SRC, d)
    if os.path.isdir(full): ok(f"dir  {d}")
    else: fail(f"dir  {d}  NO EXISTE")

# 2. Archivos críticos
section("2. Archivos críticos")
critical = [
    "settings.gradle.kts", "build.gradle.kts", "gradle.properties",
    "app/build.gradle.kts", "app/proguard-rules.pro",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/res/values/strings.xml",
    "app/src/main/res/values/colors.xml",
    "app/src/main/res/values/themes.xml",
    "app/src/main/res/drawable/ic_splash_icon.xml",
    "app/src/main/res/drawable/ic_launcher_foreground.xml",
    "app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml",
    "gradlew", "gradle/wrapper/gradle-wrapper.jar",
    "app/src/main/java/com/vertil/VertilApp.kt",
    "app/src/main/java/com/vertil/core/VertilCore.kt",
    "app/src/main/java/com/vertil/core/VertilResult.kt",
    "app/src/main/java/com/vertil/core/identity/VertilIdentity.kt",
    "app/src/main/java/com/vertil/core/prompt/SystemPrompt.kt",
    "app/src/main/java/com/vertil/core/config/VertilConfig.kt",
    "app/src/main/java/com/vertil/core/log/VertilLog.kt",
    "app/src/main/java/com/vertil/core/error/ErrorManager.kt",
    "app/src/main/java/com/vertil/model/LocalModel.kt",
    "app/src/main/java/com/vertil/model/ModelInfo.kt",
    "app/src/main/java/com/vertil/model/ModelManager.kt",
    "app/src/main/java/com/vertil/model/ModelRuntimeRegistry.kt",
    "app/src/main/java/com/vertil/model/runtime/OnnxModelRuntime.kt",
    "app/src/main/java/com/vertil/model/runtime/MockModelRuntime.kt",
    "app/src/main/java/com/vertil/model/runtime/GgufModelRuntime.kt",
    "app/src/main/java/com/vertil/model/runtime/TfliteModelRuntime.kt",
    "app/src/main/java/com/vertil/permissions/PermissionLevel.kt",
    "app/src/main/java/com/vertil/permissions/PermissionManager.kt",
    "app/src/main/java/com/vertil/tools/Tool.kt",
    "app/src/main/java/com/vertil/tools/ToolManager.kt",
    "app/src/main/java/com/vertil/tools/impl/FileTools.kt",
    "app/src/main/java/com/vertil/tools/impl/SystemTools.kt",
    "app/src/main/java/com/vertil/storage/FileEngine.kt",
    "app/src/main/java/com/vertil/automation/AutomationRule.kt",
    "app/src/main/java/com/vertil/automation/AutomationRepository.kt",
    "app/src/main/java/com/vertil/device/DeviceProfiler.kt",
    "app/src/main/java/com/vertil/activity/ActivityEntry.kt",
    "app/src/main/java/com/vertil/activity/ActivityRepository.kt",
    "app/src/main/java/com/vertil/chat/ChatMessage.kt",
    "app/src/main/java/com/vertil/persistence/VertilDatabase.kt",
    "app/src/main/java/com/vertil/persistence/entities/Entities.kt",
    "app/src/main/java/com/vertil/persistence/daos/Daos.kt",
    "app/src/main/java/com/vertil/background/MonitoringService.kt",
    "app/src/main/java/com/vertil/di/ServiceLocator.kt",
    "app/src/main/java/com/vertil/ui/MainActivity.kt",
    "app/src/main/java/com/vertil/ui/VertilViewModel.kt",
    "app/src/main/java/com/vertil/ui/theme/Color.kt",
    "app/src/main/java/com/vertil/ui/theme/Theme.kt",
    "app/src/main/java/com/vertil/ui/screens/HomeScreen.kt",
    "app/src/main/java/com/vertil/ui/screens/ChatScreen.kt",
    "app/src/main/java/com/vertil/ui/screens/ModelsScreen.kt",
    "app/src/main/java/com/vertil/ui/screens/FilesScreen.kt",
    "app/src/main/java/com/vertil/ui/screens/ActivityScreen.kt",
    "app/src/main/java/com/vertil/ui/screens/AutomationsScreen.kt",
    "app/src/main/java/com/vertil/ui/screens/PermissionsScreen.kt",
    "app/src/main/java/com/vertil/ui/screens/SettingsScreen.kt",
]
for f in critical:
    full = os.path.join(PROJ, f)
    if not os.path.isfile(full): fail(f"file {f}  NO EXISTE"); continue
    size = os.path.getsize(full)
    if size == 0: fail(f"file {f}  VACÍO")
    elif size < 20: warn(f"file {f}  muy pequeño ({size}b)")
    else: ok(f"file {f}  ({size}b)")

# 3. Gradle
section("3. Gradle")
app_build = open(os.path.join(APP, "build.gradle.kts")).read()
if 'compileSdk = 34' in app_build: ok("compileSdk = 34")
else: fail("compileSdk no es 34")
if 'applicationId = "com.vertil"' in app_build: ok("applicationId correcto")
else: fail("applicationId incorrecto")
if 'onnxruntime' in app_build.lower(): ok("ONNX Runtime presente")
else: fail("ONNX Runtime ausente")
if 'room' in app_build.lower(): ok("Room presente")
else: fail("Room ausente")
if 'splashscreen' in app_build: ok("splashscreen presente")
else: fail("splashscreen ausente")

# 4. Manifest
section("4. Manifest")
manifest = open(os.path.join(APP, "src", "main", "AndroidManifest.xml")).read()
if 'android:name=".VertilApp"' in manifest: ok("VertilApp declarada")
else: fail("VertilApp ausente")
if 'android:name=".ui.MainActivity"' in manifest: ok("MainActivity declarada")
else: fail("MainActivity ausente")
if 'READ_MEDIA_AUDIO' in manifest: ok("READ_MEDIA_AUDIO")
else: fail("READ_MEDIA_AUDIO ausente")
if 'FOREGROUND_SERVICE' in manifest: ok("FOREGROUND_SERVICE")
else: fail("FOREGROUND_SERVICE ausente")
if re.search(r'uses-permission[^>]*INTERNET', manifest):
    fail("Se declaró INTERNET — no debería estar")
else: ok("Sin INTERNET (offline-first)")

# 5. Código fuente
section("5. Anti-patrones")
kt_files = []
for root, _, files in os.walk(SRC):
    for fn in files:
        if fn.endswith(".kt"): kt_files.append(os.path.join(root, fn))

placeholder_pattern = re.compile(r"\b(TODO|FIXME|XXX|HACK)\b")
empty_catch_pattern = re.compile(r"catch\s*\([^)]*\)\s*\{\s*\}", re.MULTILINE)

placeholder_count = 0; empty_catch_count = 0
for kf in kt_files:
    text = open(kf).read()
    if placeholder_pattern.search(text):
        placeholder_count += 1; warn(f"placeholder en {os.path.relpath(kf, PROJ)}")
    if empty_catch_pattern.search(text):
        empty_catch_count += 1; fail(f"catch vacío en {os.path.relpath(kf, PROJ)}")
if placeholder_count == 0: ok("Sin placeholders")
if empty_catch_count == 0: ok("Sin catchs vacíos")

# 6. Tests
section("6. Tests")
test_files = []
for root, _, files in os.walk(TEST):
    for fn in files:
        if fn.endswith(".kt"): test_files.append(os.path.join(root, fn))
total_tests = 0
for tf in test_files:
    content = open(tf).read()
    tc = content.count("@Test")
    total_tests += tc
    if tc > 0: ok(f"  {os.path.relpath(tf, PROJ)} — {tc} @Test")
print(f"  Total tests: {total_tests}")

# Resultado
section("RESULTADO GLOBAL")
print(f"  PASS: {passed}")
print(f"  WARN: {warnings}")
print(f"  FAIL: {failed}")
print()
if failed == 0:
    print("PROJECT STATUS: SUCCESS")
    sys.exit(0)
else:
    print("PROJECT STATUS: FAIL")
    sys.exit(1)
