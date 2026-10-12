# BACKEND: статус

Дата: 12 октября 2026.

**G0 / этап 1: локальная подготовка завершена.** Публикация ветки, PR и совместный review остаются за пользователем и напарником. Реализация backend, БД и авторизации ещё не начата.

Ветка: `chore/starter-setup`. Основа: `origin/main`, commit `7824a4b79d94db816fb31de2a01fa148f4b2680c`. Локальный commit этапа имеет сообщение `chore: prepare Starter specification and G0 checks`; точный hash смотреть через `git log -1 --oneline`.

## Сделано

- Клонирован существующий `KDvibers/HackAtlas`, сохранена его начальная история. До G0 в репозитории был только README.
- Перенесён Starter, обновлены инструкции и README под фактический репозиторий, границы работы и пользовательские push/PR.
- Зафиксировано: только `api/openapi.yaml` + `api/README.md`, opaque Bearer sessions без JWT/refresh. Контракт и fixtures не изменены; новый контракт несовместим со старым Handoff, несмотря на тот же номер 0.1.0.
- Созданы [решения](DECISIONS.md) и [отчёт о расхождениях/окружении](SETUP_AUDIT.md).
- Усилена проверка контракта в `scripts/`, закреплена 21 developer-зависимость, подготовлена локальная игнорируемая `.venv` с Python 3.12.14.
- Через read-only GitHub API подтверждены аккаунт `yergaliuly`, write-доступ и привязка автора первоначального коммита. В local Git config закреплены аккаунт и подтверждённое авторство; global config не менялся.

## Реальные проверки

Все перечисленные команды завершились с exit code 0:

```powershell
.\.venv\Scripts\python.exe -m pip install PyYAML==6.0.3 jsonschema==4.26.0 openapi-spec-validator==0.9.0
.\.venv\Scripts\python.exe -m pip install rfc3986-validator==0.1.1
.\.venv\Scripts\python.exe .\scripts\validate_contract.py
.\.venv\Scripts\python.exe -m pip check
git diff --cached --check
```

- PASS: OpenAPI 3.1.0 / API 0.1.0, 14 paths, 17 operations, 24 schemas; локальные `$ref`, уникальность operationId и параметры путей.
- PASS: границы B1/B2/B3, три публичных маршрута каталога, opaque Bearer и отсутствие refresh.
- PASS: все шесть demo JSON из manifest; date/date-time/UUID/URI/email имеют активные format checkers.
- PASS: семь негативных сценариев в памяти отклонены, включая JWT, refresh, public `/me`, повтор operationId, отсутствие path parameter/x-phase и невалидный URI. Исходные файлы при этих проверках не менялись.
- PASS: `pip check` — `No broken requirements found`.
- PASS: 14 относительных Markdown-ссылок в 24 файлах существуют. Внешние URL и anchors не проверялись.
- PASS: все 15 рабочих файлов `api/`, `design/`, `mobile/` совпадают с файлами Starter по SHA-256; hash референса `fd7dd0a85b8dd39a278481cc43faf96199f8ad098fbad1e885be6ab75c2be1cf`.
- PASS: проверены состав изменений и распространённые шаблоны секретов; `.venv` исключена Git ignore. Это ограниченная проверка, не полноценный security audit.
- Независимое review общих инструкций выполнено; замечание о точности описания исходного START_HERE исправлено.

Для повторного запуска достаточно `.\.venv\Scripts\python.exe .\scripts\validate_contract.py`. Создание окружения на другой машине описано в [scripts/README](../scripts/README.md).

## Не проверено / ещё не реализовано

Нет серверных endpoints, Maven Wrapper, PostgreSQL migrations, backend CI и Java-тестов. Сборка backend, Testcontainers, безопасность работающей авторизации и мобильные/device проверки не запускались. Push, PR, merge и deployment не выполнялись.

JDK 21 и Git доступны. `mvn`, `docker`, `gh` не найдены в PATH. Для B0 нужен Maven Wrapper и доступный Docker/Compose для PostgreSQL/Testcontainers; Docker engine пока не проверен. Общая спецификация проверяется без них.

У четырёх demo-событий в catalog-page.json скопирован текст «AI-хакатон»; это отмечено для совместной правки, схема и fixtures сохранены. GitHub handle напарника и доступ к Mac/Xcode пока не подтверждены.

## Передача напарнику

Подготовлена ветка `chore/starter-setup` со Starter. Использовать только `api/`, opaque Bearer без refresh и `design/reference/approved-reference.png`. Работающего API/base URL ещё нет. M0/M1 можно планировать по общим fixtures после получения пользовательского PR и сверки спецификации.

## Следующий отдельный этап

**B0:** обеспечить Docker/Compose, проверить совместимые стабильные версии Spring Boot 4.x и PostgreSQL 17, создать Java 21 skeleton, Maven Wrapper, окружения, Flyway и базовые проверки. Не реализовывать B2/auth заранее.
