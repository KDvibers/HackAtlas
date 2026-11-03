# Final review — profile edit

Один независимый read-only reviewer, весь diff/new files от `7f67955`.
**0 Critical / 2 Important / 0 Minor.** Файлы/index reviewer не менял, повторное
независимое review не запускалось. Два Important исправлены одним проходом.

1. **Applied response lost + изменение draft теряло намерение.** Прежний diff
   относительно старого baseline мог пропустить возвращённое пользователем поле.
   Теперь uncertainFields сохраняет отправленные keys до ack/reload, retry передаёт
   их актуальные значения и не скрывает предупреждение после ввода.
   `appliedResponseLostThenRevertAndOtherEditHonorsCurrentDraft` behavioral RED
   (ошибка очищалась, серверное значение неверно) → GREEN.
2. **Schema-invalid Profile200 объявлялся успехом.** Теперь общая проверка Profile
   включает ограничения всех шести editable fields: avatar/country/lengths/
   unique bounded interests, Unicode well-formed/NUL. Wrong owner/missing fields
   по-прежнему invalid. `structurallyValidButContractInvalidProfileNeverPublishes`
   RED accepted invalid response → GREEN. Draft при ошибке остаётся в памяти.

При live-приёмке обнаружена дополнительная UI-регрессия: преобразование `kz`
в upper-case прямо в onValueChange приводило к потере второй буквы. Raw input
теперь сохраняется без преобразования; uppercase выполняется на границе PATCH.
Тот же native-сценарий RED → GREEN, включая проверку KZ в реальной БД. На исходном screenshot
выбранные chips были default Material gray; теперь цвета/контуры используют
прежние Atlas Primary/Surface/Border, без новой визуальной системы.

После перерыва отдельная native-проверка выявила ещё одну регрессию: закрытие
редактора после foreground401 запускало route refresh, который заменял
SignInRequired на SignedOut и скрывал причину повторного входа. restore без токена
теперь сохраняет SignInRequired; успешный login или явный logout меняют его как
прежде. `revokedForegroundDiscardsDraftAndProfileRefreshKeepsSignInRequired`
воспроизвёл expected SignInRequired / was SignedOut (RED), затем GREEN/full228.
Это находка исполнителя после review; новый независимый reviewer не запускался.

Полный regression после этих UI-правок: **228 PASS**, 0 failures/errors/skipped, Android build/lint0/
release gate PASS. Native/device результаты — отдельно android-acceptance.json;
reviewer не запускал тесты или UiAutomator параллельно.

**Android-приёмка3ноября:6/6 PASS на API36.** Один полный запуск на неизменённом
APK e95ba0373662b5172f98953b68cadd961fa386170e6ec0d95ea66a2d4a1cd6e4.
Отзыв1active session, удаление draft/credential/cache, сохранение guest bytes,
360dp/font130 и валидация подтверждены. Новый XML dump retry в финальном прогоне
не использовался (0), restoreErrors[]. Скриншоты просмотрены исполнителем.
Два harness selfcheck PASS: запись false-отчёта при потере device и лимит свежего
наблюдения после read timeout. Продуктовый код не менялся;228/full build/lint/release
относятся к прежней проверке2ноября. Дополнительный независимый reviewer не вызывался.

Отдельная находка совместимости: API34 образ/tzdata2023a отвергает согласованный
MINUTE Алматы; API34_TZDATA.md содержит воспроизведение и источник IANA.
Валидация/даты не ослаблялись. Старые неудачные прогоны сохранены в history.

## Rulings и ограничения

- iOS/Keychain/native UI/Swift interop не выполнены на Windows; source/shared
  поддержка не считается iPhone-приёмкой. Mac может выявить native несовместимости.
- Android visual/keyboard/font130/live B2 проверяет исполнитель, TalkBack и физическое
  устройство здесь отдельно не приняты. Независимый reviewer оценивал source,
  не воспроизводил runtime доказательства.
- Message18 — draft context, не требование внедрить proposed auth lifecycle;
  отдельная mobile оценка подготовлена, canonical API untouched. Нет решения
  от имени пользователя о SMTP/pending/forced recovery.
- Пользовательские `.agents/`/`.interface-design/` вне изменений. Источники UI
  переиспользованы; fonts/assets/стек не менялись. Deferred Minor отсутствуют.
