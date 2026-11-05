# Commit/push вручную

Ветка: `fix/mobile-tzdata-compat`, base: `main`. Агент не создавал commit/PR и не делал push.

```powershell
Set-Location 'C:\Users\user\.codex\worktrees\mobile-catalog-api\HackAtlas'
git status --short
git branch --show-current
git add -- mobile docs/STATUS_MOBILE.md docs/TECHNOLOGY_DECISIONS.md docs/superpowers/plans/2026-11-03-android-tzdata-compat.md docs/superpowers/specs/2026-11-03-android-tzdata-compat.md
git diff --cached --stat
git commit -m "fix(mobile): support older Android timezone data"
git push -u origin fix/mobile-tzdata-compat
```

`.agents/` и `.interface-design/` не входят в команды staging.
APK/logs/AVDs в mobile/build игнорируются Git.

Заголовок PR: **fix(mobile): support older Android timezone data**

Описание: [PR_DESCRIPTION.md](PR_DESCRIPTION.md).
Создать вручную: https://github.com/KDvibers/HackAtlas/compare/main...fix/mobile-tzdata-compat?expand=1
