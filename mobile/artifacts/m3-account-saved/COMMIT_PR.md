# Команды для пользователя

```powershell
Set-Location 'C:/Users/user/.codex/worktrees/mobile-catalog-api/HackAtlas'
git branch --show-current
git status --short
git add -- mobile/ docs/STATUS_MOBILE.md docs/superpowers/specs/2026-11-01-mobile-account-saved-design.md docs/superpowers/plans/2026-11-01-mobile-account-saved.md
git diff --cached --check
git diff --cached --stat
git commit -m "feat(mobile): sync account bookmarks and confirm guest copy"
git push -u origin feat/mobile-account-saved
```

Не добавлять `.agents/`, `.interface-design/`, local configs, build и credentials.

Создать PR в браузере:
[main ← feat/mobile-account-saved](https://github.com/KDvibers/HackAtlas/compare/main...feat/mobile-account-saved).

Заголовок: **feat(mobile): sync account bookmarks and confirm guest copy**

```powershell
Get-Content -Raw mobile/artifacts/m3-account-saved/PR_DESCRIPTION.md | Set-Clipboard
```

GitHub CLI необязателен. Если установлен и авторизован:

```powershell
gh pr create --repo KDvibers/HackAtlas --base main --head feat/mobile-account-saved --title 'feat(mobile): sync account bookmarks and confirm guest copy' --body-file mobile/artifacts/m3-account-saved/PR_DESCRIPTION.md
```

Agent оставляет изменения без staging/commit/push/PR/merge.
