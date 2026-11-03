# M3 profile edit

Общий Android/iOS Compose editor использует прежний Starter `PATCH /me`.
Editable: displayName, city, countryCode, roleLabel, avatarKey, interests.
Email/permissions/stats не редактируются; загрузки фотографий нет.
Новых библиотек, шрифтов или assets не добавлено.

## Семантика и жизненный цикл

- Нормализованный черновик сравнивается с baseline. Отсутствующее поле остаётся
  нетронутым; очистка nullable city/country/role передаёт JSON null. Interest set
  передаётся целиком, только при изменении набора. Никакого отправления пустого
  списка из-за ошибки metadata; известные прежние IDs сохраняются/могут удаляться.
- Лимиты совпадают с действующим контрактом/сервером: Unicode code points,
  displayName1..60, city/role<=100, country uppercase2ASCII или null,
  HOODED/INITIALS, до20unique interests ID1..50. NUL/unpaired surrogates запрещены.
- PATCH выполняет credential-owning AuthSessionClient; expected UUID сверяется
  внутри auth mutex, метод/URL/allowlist фиксированы. 200 проходит Profile schema
  checks и совпадение userId перед публикацией. 401 отзывает local session; другие
  ошибки не маскируются успехом. Токен/пароль вне UI, Bearer только своему API.
- ProfileEditStore один на process runtime. Draft/baseline живут только в памяти,
  не в Bundle/saveable/disk. Rotation/foreground сохраняют same-owner draft, GET me
  не перезаписывает введённое. Logout/owner change удаляют draft; epoch отклоняет
  поздний UI success. Уже отправленный PATCH может завершиться после закрытия UI,
  его нельзя откатить Back; серверный результат сериализован с logout.
- Dirty cancel/reload требуют подтверждения. Двойной Save блокируется. Успех —
  только после корректного200. Ошибка оставляет draft и объясняет неоднозначность:
  сервер мог принять изменения до потери ответа. Набор отправленных полей хранится
  до подтверждения/reload: если пользователь вернул поле к старому значению, retry
  всё равно явно отправляет это текущее значение, не теряет намерение пользователя.
- Offline mutations/outbox нет. Bookmarks/import/calendar не меняются от edit.

## Проверки и использование

Войти → Профиль → «Редактировать профиль» → изменить/отменить/сохранить.
Interests загружаются из действующих metadata, доступен retry. Базовые поля можно
редактировать при недоступных metadata, прежний interest set остаётся нетронутым.

```powershell
# Из mobile/, JDK21/SDK36
.\gradlew.bat :shared:testDebugUnitTest --rerun :androidApp:assembleDebug :androidApp:lintDebug :androidApp:verifyReleaseNoDemoFixtures --offline --console=plain '-Phackatlas.debugSource=REMOTE' '-Phackatlas.debugApiBaseUrl=http://10.0.2.2:8772/api/v1/' '-Phackatlas.allowDebugHttp=true'
```

Результаты — `artifacts/m3-profile-edit/verification.json`, native JSON/screenshots.
`scripts/android_profile_edit_smoke.py` использует только disposable local B2/DB8772
и emulator-* с synthetic account/DEMO catalogue. Проверяет БД и UI, сохраняет
guest bytes/display/network; не запускать против пользователя/production.

Shared228tests/Android build/lint/release gate проверены. iOS build/Swift interop/
Keychain/actual device UI остаются открытыми на Windows; нужен Mac/Xcode/HTTPS B2.

Android-приёмка3ноября:6/6PASS в одном полном запуске на Android16/API36,
2GB,4cores,swiftshader,tzdata2025a. SHA установленного APK совпал с прежней сборкой;
код приложения не менялся, прежний228unit/build/lint/release прогон не повторялся.
Отозвана1активная сессия, сообщение видно, draft/credential/cache удалены, guest
bytes прежние. Readback1080px/480dpi=360dp,font_scale1.3; ошибка имени/Save доступны.
Скриншоты просмотрены. RestoreErrors=[], повторов UI-наблюдения в финальном прогоне0.

API34 с конкретной старой tzdata2023a отклоняет корректный MINUTE для Алматы.
Это отдельный незакрытый вопрос, детали в artifacts/m3-profile-edit/API34_TZDATA.md.
Правила/даты не подменялись. API36PASS не означает проверку всех версий Android.
Тестовые B2/DB8772 остановлены, контейнер/private env удалены; временные AVD
остановлены/убраны в Корзину. Исходные AVD не сбрасывались.

## Сообщение бэкендчика

`message (18).txt` — unapproved email verification/password reset proposal,
не действующий API и не разрешение менять Starter. Технические замечания и текст
для ручной передачи — `artifacts/m3-profile-edit/BACKEND_DRAFT_REVIEW.md`.
Эти endpoint, emailVerified/pending, SMTP и recovery в данном этапе не реализованы.
Application marks/reminders и следующие блоки также не входят в scope.
