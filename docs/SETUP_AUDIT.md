# Аудит G0 — 12 октября 2026

## Исходное состояние

Рабочая папка была пустой. Клонирован существующий `KDvibers/HackAtlas`, основная ветка `main`, исходный commit `7824a4b79d94db816fb31de2a01fa148f4b2680c`. В нём был только README с названием проекта. Репозиторий заново не инициализировался; исходный commit сохранён. Создана ветка `chore/starter-setup`.

Существующей Java/Kotlin реализации, схемы БД, авторизации и AGENTS.md не было. Поэтому противоречащая авторизация не обнаружена и не изменялась. README дополнен после сравнения; остальные 36 файлов Starter перенесены в отсутствующие пути.

## Найденные расхождения

| Проверка | Находка | Действие |
|---|---|---|
| Старые материалы и новый источник | Handoff описывал JWT/refresh и другой контракт при том же номере 0.1.0 | По решению пользователя каноничен только Starter; Handoff в репозиторий не переносился |
| Авторизация Starter | OpenAPI, API README, backend AGENTS и backend prompt согласованы: opaque Bearer, без refresh | Контракт оставлен без изменения; реализации ещё нет |
| Инструкции начального старта | Starter содержал общий сценарий создания репозитория и альтернативу для существующего | START_HERE и WORKFLOW конкретизированы под существующий `KDvibers/HackAtlas` |
| Граница работы | Вложенный backend prompt предлагает B0+B1; пользователь сейчас поручил этап 1/G0 | Текущая граница записана в AGENTS и DECISIONS; backend не создавался |
| Git author и HTTPS-аккаунт | Global Git author был `whyquas`; GCM содержал `whyquas` и `yergaliuly`, явного выбора для repo не было | Выбран `yergaliuly` локально; авторство проверено отдельно и также настроено только в этом клоне |
| Demo-тексты | В `api/examples/catalog-page.json` у Fintech, GameDev, GreenTech и Closed Demo скопировано описание «AI-хакатон» | Отмечено для совместного review; fixtures и контракт сохранены без изменения |
| Проверки исходного комплекта | Исходный скрипт проверял JSON Schema/fixtures, но не весь OpenAPI | Добавлена воспроизводимая специализированная проверка; см. scripts/README.md |

Ссылки старого Handoff `/catalog/filters`, `/me/bookmarks`, `contracts/openapi.yaml`, JWT/refresh не являются альтернативами для разработки. Актуальные значения берутся из Starter: `/metadata/filters`, `/me/saved`, `api/openapi.yaml`, opaque sessions.

## GitHub и авторство

Git Credential Manager содержит оба аккаунта; выбор для этого репозитория закреплён через local `credential.https://github.com.username=yergaliuly`.

Read-only GitHub API `/user` подтвердил `yergaliuly` (ID 244538599). `/repos/KDvibers/HackAtlas` подтвердил приватный репозиторий, ветку main и права pull/push/admin. Правила защиты ветки этим не проверялись; actual push не выполнялся.

GitHub API первоначального коммита связал его автора `softache` с аккаунтом `yergaliuly`. Подтверждённые name/email автора этого коммита применены в local Git config. Global настройки не изменялись. Tokens и HTTP headers не сохранялись в файлы и не выводились в отчёт.

## Окружение

| Инструмент | Фактически проверено |
|---|---|
| ОС | Windows, PowerShell |
| Git | 2.55.0.windows.3 |
| JDK | Eclipse Temurin 21.0.12.1 LTS; java/javac 21.0.12 |
| Maven | `mvn` отсутствует в PATH; Wrapper будет создан в B0 |
| Docker / Compose | `docker` отсутствует в PATH; доступность engine/Compose не подтверждена |
| GitHub CLI | `gh` отсутствует в PATH; использованы Git/GCM и read-only API |
| Python | Системный alias ведёт в Windows Store; для локальной `.venv` использован доступный Python 3.12.14 из runtime Codex |

Python нужен только developer-проверкам OpenAPI. `.venv` локальная и игнорируется Git; зависимости закреплены в `scripts/requirements-contract.txt`. Backend runtime остаётся Java.

## Проверки и оставшаяся работа

Точные команды и результаты текущего прогона записаны в [STATUS_BACKEND](STATUS_BACKEND.md). Исторический [KIT_VALIDATION](KIT_VALIDATION.md) относится к подготовке ZIP, а не заменяет текущие проверки.

На B0 нужно создать Maven Wrapper, подобрать совместимые стабильные версии Spring Boot 4.x и PostgreSQL 17, обеспечить Docker/Compose для PostgreSQL/Testcontainers. Их отсутствие не мешает проверить спецификацию G0. Сборка backend, миграции, интеграционные и device-тесты сейчас не выполнялись: соответствующий код отсутствует.

Напарнику требуется получить подготовленную пользователем ветку/PR и сверить тот же API. Мобильный GitHub handle, доступ к Mac/Xcode и deployment пока не подтверждены. CODEOWNERS остаётся примером с placeholders до получения второго аккаунта.
