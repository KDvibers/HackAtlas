# N2: Windows/Android integration handoff

**Goal:** проверить закрытый backend/Mailpit с Windows и дать другу воспроизводимые
настройки Android Emulator API36 Pixel8 x86_64 и regression API34/old tzdata.
**Architecture:** существующие WSL launcher/Compose, loopback ports, стандартный
emulator host alias; optional adb reverse, без LAN bind/внешнего deployment.
**Spec:** [принятая для интеграции матрица](../specs/2026-11-05-account-lifecycle-client-matrix.md),
merged PR29, main fb05a31. Canonical0.1.1 сохраняется.
**Execution:** один исполнитель, TDD для минимального launcher flag. Commit/push/PR — пользователь.

## Границы

- Не менять auth/runtime/API/mobile. iOS отдельно на Mac, Android device results у друга.
- Локально SDK/adb не обнаружены: не устанавливать их и не заявлять emulator PASS.
- Не менять WSL/firewall/system Docker permissions. Все host ports только127.0.0.1.
- DEMO только explicit --demo в serve, данные isDemo=true; clock остаётся системным.
  Clock.fixed заморозил бы lifecycle cooldown/expiry и не подходит для живого E2E.

## Проверяемые шаги

- [x] RED→GREEN tests в scripts/lifecycle/test_local.py: --demo включает seed,
  унаследованный fixed clock не проходит, default seed off, flag только serve.
- [x] Минимальный local.py flag, все lifecycle/contract Python checks.
- [x] Maven verify на main backend, запуск own disposable N2 с real SMTP/DEMO.
- [x] Windows HTTP →WSL: health,3 public GET,isDemo/ID, pending/verification/reset,
  old Bearer rejection/new login, no-store. Не печатать credentials/code/grant.
- [x] В Windows/Android runbook описать WSL prerequisites, адреса, debug Gradle
  свойства, точечный adb reverse с serial, cleanup и E2E evidence для обеих API.
- [x] Записать реальные PASS/непроверенное, обновить свой STATUS_BACKEND; scope,
  ссылки,Gitleaks,diff whitespace. Удалить только собственные disposable resources.

## Решение по окружению

Backend/PostgreSQL/Mailpit друг поднимает на своём Windows/WSL компьютере: localhost
на нашей машине не является доступным ему URL. Windows browser использует127.0.0.1,
emulator —10.0.2.2 или явный adb reverse. Сеть подтверждать по слоям Windows→WSL,
затем emulator→Windows; наши host checks не заменяют прогон его приложения.

Фактический RED Windows probe: Mailpit18025 TCP PASS, API8081 timeout;
API отвечает в WSL, /proc/net/tcp6 показывает IPv4-mapped IPv6 LISTEN, Windows
wslrelay не создал8081 listener. Проверить preferIPv4Stack=true только в local
launcher; bind127.0.0.1 и настройки WSL/firewall не менять. Доказательство GREEN —
тот же native Windows HTTP probe после перезапуска, не один unit assertion argv.


## Итог

47unit+309IT PASS, lifecycle11+contract13 Python PASS, canonical13fixtures PASS,
N2 actualHTTP115 PASS. Native Windows24checks PASS через настоящий WSL CLI/Mailpit,
5published DEMO, clocksystem, IPv4 RED→GREEN. SDK/adb отсутствуют в доступном окружении:
Android36/34 и iOS NOT RUN. Own resources/secret state очищены. Scope6files, links,
Gitleaks, diff whitespace PASS; staging пуст. Пользователь делает commit/push/PR.
