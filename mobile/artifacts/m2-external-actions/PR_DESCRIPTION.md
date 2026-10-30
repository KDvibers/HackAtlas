## Этап и изменение

M2: кнопка регистрации на экране подробностей открывает безопасный HTTPS-сайт через ОС; «Поделиться» открывает системное меню с названием события и публичной ссылкой. Реализованы Android Intents и iOS UIKit-адаптеры. Действия доступны из уже загруженных подробностей и offline snapshot без нового запроса к API.

Закрытая, отменённая, завершённая или неизвестная регистрация и отсутствие безопасного URL отключают CTA. Официальный сайт доступен отдельно. Bundled/server DEMO вместо перехода показывает предупреждение и ссылку; текст share явно отмечает вымышленное событие. Нажатия не отмечают участие и не отправляют заявку.

## Проверка

`assembleDebug`, `:shared:testDebugUnitTest --rerun`, `lintDebug`, `verifyReleaseNoDemoFixtures` — PASS; 141 тест, 0 failures/errors/skipped, lint0. Starter validator и 6 fixtures — PASS. Точные команды и результаты: `docs/STATUS_MOBILE.md`, `mobile/artifacts/m2-external-actions/verification.json`.

Android smoke проверяет реальные системные окна, возврат/отмену и scroll, cold offline snapshot, server DEMO и disabled states; никаких отправок получателям. Полный итог — `mobile/artifacts/m2-external-actions/smoke.json` (`status=PASS`, `fullAcceptance=true` обязательны). Тестовые guest bytes/settings восстанавливаются.

## Интерфейс

Утверждённые иллюстрации, шрифты, палитра и структура экрана сохранены; новых ассетов нет. Галерея: `mobile/artifacts/m2-external-actions/review.html`. Native share оформляет ОС. На скриншотах synthetic MOCK-данные отдельного fixture-сервера, не реальные события B1.

## API и данные

OpenAPI, backend и формат гостевого хранения не изменены. Только Starter `registrationUrl`, `officialUrl`, `sourceUrl` и существующие статусы. Auth/refresh/JWT не добавляются. Fixture-сервер существует только в Python smoke, в APK не встраивается. Release gate запрещает bundled DEMO/dev-http.

## Риски / ограничения

iOS compile/link и работа на iPhone/iPad ещё не проверены: нужен Mac/Xcode по checklist в `mobile/README.md`. На Android подтверждена передача HTTPS-ссылки в Chrome; его первый запуск показывает onboarding, загрузка сайта и реальная регистрация не заявляются. Отдельно остаются проверки отказа native handler на целевых устройствах. Доставка сообщения и загрузка сайта без сети не гарантируются.

Одно независимое review: 0 Critical / 0 Important / 1 Minor. Отложено усиление структуры IPv6 в URL: отдельные некорректные IP-literal адреса могут включать CTA и открывать неработающую ссылку; обход HTTPS не установлен. Подробности: `mobile/artifacts/m2-external-actions/REVIEW.md`.

## Сообщение напарнику

Клиент умеет открывать внешнюю регистрацию и системный share, в том числе из сохранённой копии. Контракт без изменений, новые endpoints не нужны. DEMO не открывает регистрацию. Для совместной iOS-проверки нужен HTTPS-стенд и согласованное OPEN-событие с настоящей публичной ссылкой. M3 не начат.
