# Android tzdata compatibility — проверка3ноября2026

Scope: исправление каталога на старой Android tzdata. Base f72e248,
ветка fix/mobile-tzdata-compat. API/backend/auth/UI не изменены.

## Команды и результаты

JDK21 из Android Studio; SDK API36, minSdk26. После исправления native linkage:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Android/Android Studio/jbr'
$env:ANDROID_HOME = 'C:/Users/user/AppData/Local/Android/Sdk'
& mobile/gradlew.bat -p mobile :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain --no-daemon --max-workers=2 '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process' '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8772/api/v1/' '-Phackatlas.allowDebugHttp=true'
& 'C:/Users/user/Desktop/HackAtlas/mobile/build/pr4-contract-venv/Scripts/python.exe' scripts/validate_contract.py
```

BUILD SUCCESSFUL1m30s,236 tests0 failures/errors/skipped; lint No issues;
release gate PASS. Contract validator PASS API0.1.1/root6+planned7fixtures.
Логи: mobile/build/tzdata-compat/{full-final-verification,contract-verification}.log.
Resource RED: missing TZDB.dat; selector RED: bundled newer than OS not selected.
GREEN после реализации. Native первый вариант дополнительно выявил недоступный
на Android getVersions; заменён публичным ICU API. Полный suite повторён после него.

Native helper (Windows Python из указанного venv), task-owned emulator-5560:

```powershell
python mobile/scripts/android_tzdata_smoke.py --adb 'C:/Users/user/AppData/Local/Android/Sdk/platform-tools/adb.exe' --serial emulator-5560 --label api34 --apk mobile/build/tzdata-compat/HackAtlas-tzdata-debug.apk
# На task-owned HackAtlas_Tzdata_API36: та же команда с --label api36.
```

Helper отказывается работать с чужим AVD до HTTP-сервера и app mutations:
отдельный guard selfcheck PASS. HTTP fixture replies проверены по текущим
OpenAPI response schemas; негативный случай меняет только runtime-copy MINUTE.
Не выполнялись SQL/auth/backend mutations и настоящая B2-приёмка.

## Native evidence

API34/2023a: baseline E95 APK воспроизводит InvalidResponse. Fixed73916988
прошёл4/4: каталог5fixtures, deadline18/start10 Asia/Almaty, guest saved после
restart offline, настоящий invalid-MINUTE отвергнут. failure=null,restoreErrors=[].
Изначальная система по-прежнему переводит05Z в11:00+0600. Android правила глобально
не обновлялись. Before/after читают идентичные валидные HTTP байты.

API36/2025a:4/4 PASS, failure=null,restoreErrors=[],0 observation retries.
Тот же установленный APK73916988; системное05Z->10:00+0500 прежнее. Native
финальная конфигурация:2048MB/4cores/lavapipe. Валидный HTTP body совпадает
с baseline/API34. Все неполные попытки сохранены в android-history.json и не
подменяют успешную приёмку. Host QEMU0xc0000005 подтверждён Windows event1000;
успешный API34 использует swiftshader, Vulkanoff,1536MB/4cores.

Screens API34 catalog/start/offline/invalid просмотрены: читаемое время,
сохранённый дизайн M1 и корректные DEMO/snapshot notices. UI-код не менялся.

## Итоговое независимое ревью

PASS: один fresh read-only reviewer всей задачи; Critical0/Important0/Minor0.
Проверены runtime ICU API/ART branch, выбор версий, строгая валидация, Android-only
зависимость/TZDB/лицензия и smoke safety. Рецензент не менял файлы/не запускал
Gradle/ADB; доступные отчёты проверены чтением. API36 на момент ревью ожидал
проверки исполнителем. Решение: отмеченные вне scope iOS old tzdata, физические
устройства/другие API, глобальные правила, Calendar.today, UI/backend/auth
оставлены вне этой задачи и явно записаны в ограничениях.

## Ограничения

Native iOS/Mac, физический телефон и API26–33/35 не запускались. iOS resolver
прежний. При устаревании обоих rule providers нужно обновлять bundled tzdata.
Тестовый REMOTE APK направлен на10.0.2.2:8772, сервер fixtures живёт только в smoke
процессе. Артефакт не подтверждает доступность постоянного Java backend.

Commit/push/PR выполняет пользователь. Следующий шаг — отдельный PR этой правки,
без автоматического перехода к новым блокам M3.

Cleanup: оба task-owned AVD остановлены, проверенные workspace paths перенесены
в Корзину, их registry удалены; исходные AVD не изменены. Fixture process завершён.
API36 start/offline screenshots просмотрены, источник и точное время читаемы.
