# M3 auth UI — итоговое ревью

Один независимый read-only reviewer проверил весь текущий diff и новые файлы от
`066570d119dc66c21be315bcca2cd55af4f3e531`. Никаких commit/push/изменений reviewer
не выполнял. Второе независимое ревью не запускалось.

Результат: **0 Critical / 1 Important / 1 Minor**.

## Important: iOS lifecycle

SwiftUI WindowGroup не доставляет scene activation в UIApplicationDelegate
applicationDidBecomeActive/applicationWillResignActive. Это оставляло поля формы
после background и пропускало GET /me при возвращении на профиль. Исправлено:
App читает aggregate scenePhase и вызывает shared hooks через single-argument
onChange (deployment target iOS16). Delegate оставлен для termination.

Основание: [Apple UIKit scene lifecycle](https://developer.apple.com/documentation/uikit/transitioning-to-the-uikit-scene-based-life-cycle),
[SwiftUI ScenePhase](https://developer.apple.com/documentation/swiftui/scenephase).
Общая проверка background revision/foreground protected401 покрыта MockEngine.
**Native iOS RED→GREEN/compile/runtime на Windows невозможны и не заявляются.**
Исходники исправлены; завершение приёмки этой платформы требует Mac.

## Minor, оставлен явно

Принятый submit очищает пароль, но оставляет email/имя для повторной попытки;
в spec есть более широкая формулировка «очистить значения формы при submit».
После background, смены формы или ухода с неё очищаются все поля. Данные формы
не попадают в saveable/process state или диск. Потребность очищать также имя/email
при ошибочной отправке остаётся отдельным небольшим UX-решением.

## Границы оценки

- Нативные iOS сборка, Swift interop, Keychain, lockscreen и lifecycle — открыты.
- Узкие/крупный текст/клавиатура и полный live smoke проверяются исполнителем;
  reviewer не запускал эмулятор или тесты параллельно.
- Финальные shared tests/lint/release gate проверяет исполнитель после изменений.
- Saved sync/import, profile edit, reminders, deletion, verification/reset и
  public launch не входят в этот блок.
- Backend/API/deployment и новый криптографический аудит прежнего secure storage
  не входят в review; серверная production-готовность не заявляется.
- Пользовательские `.agents/` и `.interface-design/` не изменялись и не включаются
  в будущий commit. Визуальный system.md использовался только как контекст.

Android-результаты и ограничения — в `verification.json` и `android-smoke.json`.
