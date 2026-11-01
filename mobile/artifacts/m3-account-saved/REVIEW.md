# Итоговое review — M3 account saved

Один независимый read-only reviewer проверил весь diff и новые файлы от
`991be66` (merged PR20). Пользователь выполняет commit/push; agent не менял index.
Повторное независимое review не запускалось. **0 Critical / 3 Important / 0 Minor.**

## Исправления одним проходом

1. Открытая account-копия могла остаться после успешного refresh без этого события.
   Теперь UI сверяет наличие в актуальных snapshots, DetailStore рефетчит public
   detail. Archived404 означает недоступность, старый CTA не отображается.
   Regression `removedAccountSnapshotRefetchesPublicDetailAndRejectsArchivedCta`:
   RED отсутствующей reconciliation API → GREEN. Дополнительно native archive smoke.
2. Ошибка account-save была видна только в saved/profile/calendar. Добавлен общий
   dialog в catalogue/detail с понятным неоднозначным результатом и refresh/ack.
   Native regression: offline save в detail не показывал сообщение (RED), затем
   проверен на исправленном APK; результат — `review-error-smoke.json`.
3. При одинаковом total страницы могли образовать смешанный состав и заменить
   offline-копию. Для multi-page refresh добавлена повторная упорядоченная сверка;
   mismatch сохраняет предыдущий cache с ошибкой. Behavioral regression
   `sameTotalPaginationRaceKeepsPreviousCompleteCache`: RED changed bytes → GREEN.

Полный финальный shared suite **211 PASS**, Android build/lint0/release gate PASS.
Live-результаты приведены отдельно в verification.json; reviewer не запускал тесты,
ADB или серверы параллельно с исполнителем.

## Rulings / ограничения

- `asOf` — server time, не snapshot revision. Двойная сверка состава — ограниченная
  защита, не обещание транзакционного HTTP snapshot. Предел200 страниц; ошибка
  сохраняет предыдущую копию. Для строгой согласованности нужен отдельный API contract.
- iOS Foundation adapter/source/host просмотрены, но compile/atomic file protection/
  backup exclusion/Keychain/runtime не подтверждены. Требуется Mac/iPhone.
- Live B2/Android, два аккаунта, визуал и фактические тесты проверены исполнителем;
  независимое source review не заменяет повторное устройство/CI review напарника.
- `.agents/` и `.interface-design/` пользовательские, вне изменений.
- Вне этапа: outbox/offline mutations, profile edit/application marks/reminders,
  deletion/reset/verification, production audit и публикация. Новых Minor нет.
