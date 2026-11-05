# PR #25: повторный init-volumes

Проверен [упавший PR run](https://github.com/KDvibers/HackAtlas/actions/runs/41644693211),
commit `98f1cbeb3847009865c1be9ff8616a74316c7dfe`. Push run того же commit прошёл.
Contract/Gitleaks/Maven318/HTTP593/OSV106 прошли; ошибка в B4 rehearsal после обычного
backup/restore, при подготовке обновления отдельного legacy V6 restore.

Artifact commands.log показывает: Compose повторно запустил init-volumes, service
вернул exit1, migrator ещё не начал выполнять Flyway. Внутренний stderr initializer
не сохранён, поэтому конкретный ошибочный файл из GitHub run не установлен.

Подтверждённый дефект: initializer рекурсивно делает chown на уже работающих томах
PostgreSQL/Caddy. Такой обход меняет метаданные существующих файлов и может встретить
файлы, которые сервис успел удалить/переименовать. Это объясняет нестабильность и
устранено без повторных попыток, ослабления permissions или отключения проверки.

Теперь chown/chmod применяются только к трём корням томов. Содержимое создают прежние
service UID999/10001. Исправление чужих/старых descendant permissions не автоматизируется.
Новая реальная проверка повторно запускает initializer при работающих PostgreSQL и
proxy и сравнивает uid/gid/mode/ctime собственного marker. На прежнем коде наблюдался
RED (метаданные изменены); на исправленном коде полный rehearsal PASS, включая
V6→V7→V8/quarantine/approval/cleanup. Все 49 Python B4 tests PASS, процессы UID1000.
Java/API/schema код не менялся; использован тот же собранный N1 JAR из основной копии.
Remote CI после исправления ещё не запускался: commit/push делает пользователь.

Отдельный checkout оставлен на ветке feat/account-lifecycle-foundation; N2 WIP в
основной копии сохранён. Для отправки в существующий PR #25:

```powershell
Set-Location 'C:\Users\ThundeRobot\.codex\worktrees\pr25-volume-init-fix\HackAtlas'
git status --short --branch
git add infra/release/compose.yaml scripts/b4/run_rehearsal.py scripts/b4/README.md
git add docs/STATUS_BACKEND.md docs/research/2026-11-03-pr25-volume-initialization.md
git diff --cached --check
git diff --cached --stat
git commit -m "fix(infra): preserve live volume contents during initialization"
git push origin feat/account-lifecycle-foundation
```

Новый PR не нужен. GitHub HTTPS account должен оставаться yergaliuly; identity
проверяется отдельно от credential account. N2 пока базируется на N1; слияние и
последующее обновление ветки выполняются отдельно, без автоматического merge.
