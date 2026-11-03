# Commit / push / PR

```powershell
Set-Location 'C:/Users/user/.codex/worktrees/mobile-catalog-api/HackAtlas'
git branch --show-current
git status --short
git add -- mobile/ docs/STATUS_MOBILE.md docs/superpowers/specs/2026-11-01-mobile-profile-edit-design.md docs/superpowers/plans/2026-11-01-mobile-profile-edit.md
git diff --cached --check
git diff --cached --stat
git commit -m "feat(mobile): add profile editing with partial PATCH"
git push -u origin feat/mobile-profile-edit
```

Не добавлять `.agents/`, `.interface-design/`, build/local configs/credentials.

Создать PR в браузере:
[main ← feat/mobile-profile-edit](https://github.com/KDvibers/HackAtlas/compare/main...feat/mobile-profile-edit).

Заголовок: **feat(mobile): add profile editing with partial PATCH**

```powershell
Get-Content -Raw mobile/artifacts/m3-profile-edit/PR_DESCRIPTION.md | Set-Clipboard
```

GitHub CLI необязателен. Если уже установлен/авторизован:

```powershell
gh pr create --repo KDvibers/HackAtlas --base main --head feat/mobile-profile-edit --title 'feat(mobile): add profile editing with partial PATCH' --body-file mobile/artifacts/m3-profile-edit/PR_DESCRIPTION.md
```

Agent не выполнял staging/commit/push/PR/merge.
