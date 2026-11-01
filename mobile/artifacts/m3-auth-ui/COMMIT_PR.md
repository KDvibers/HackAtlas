# Commit / push / PR

Из текущего worktree, после просмотра diff. `.agents/`, `.interface-design/`,
build/local credentials не включаются в commit.

```powershell
Set-Location 'C:/Users/user/.codex/worktrees/mobile-catalog-api/HackAtlas'
git branch --show-current
git status --short
git add -- mobile/ docs/STATUS_MOBILE.md docs/superpowers/specs/2026-11-01-mobile-auth-ui-design.md docs/superpowers/plans/2026-11-01-mobile-auth-ui.md
git diff --cached --check
git diff --cached --stat
git commit -m "feat(mobile): connect auth screens and native session lifecycle"
git push -u origin feat/mobile-auth-ui
```

Создать PR в браузере после push:
[main ← feat/mobile-auth-ui](https://github.com/KDvibers/HackAtlas/compare/main...feat/mobile-auth-ui).

Заголовок: **feat(mobile): connect auth screens and native session lifecycle**

Скопировать описание:

```powershell
Get-Content -Raw mobile/artifacts/m3-auth-ui/PR_DESCRIPTION.md | Set-Clipboard
```

Если GitHub CLI уже установлен и авторизован, альтернативная команда:

```powershell
gh pr create --repo KDvibers/HackAtlas --base main --head feat/mobile-auth-ui --title 'feat(mobile): connect auth screens and native session lifecycle' --body-file mobile/artifacts/m3-auth-ui/PR_DESCRIPTION.md
```

Agent не выполнял staging, commit, push, создание PR или merge.
