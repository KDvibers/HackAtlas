# Ручной commit/push и PR

Android-приёмка завершена:10/10 PASS;268shared/build/lint/release gate PASS.
Отчёты и описание PR обновлены.
Автоматических commit/push/создания PR не было.

Из PowerShell:

```powershell
Set-Location 'C:\Users\user\.codex\worktrees\mobile-catalog-api\HackAtlas'
git branch --show-current
git status --short
git add -- mobile docs/STATUS_MOBILE.md docs/superpowers/plans/2026-11-05-mobile-application-marks.md docs/superpowers/specs/2026-11-05-mobile-application-marks-design.md
git diff --cached --check
git diff --cached --stat
git commit -m "feat(mobile): add self-reported application marks"
git push -u origin feat/mobile-application-marks
```

Перед commit проверь staged diff; `.agents/`, `.interface-design/`, build/env/SDK
в этот этап не входят. PR base: `main`, compare: `feat/mobile-application-marks`.
Заголовок: **feat(mobile): add self-reported application marks**.
Описание: [PR_DESCRIPTION.md](PR_DESCRIPTION.md).

Если GitHub CLI установлен и настроен, после push:

```powershell
gh pr create --repo KDvibers/HackAtlas --base main --head feat/mobile-application-marks --title 'feat(mobile): add self-reported application marks' --body-file mobile/artifacts/m3-applications/PR_DESCRIPTION.md
```

Иначе создай PR в веб-интерфейсе GitHub с тем же заголовком и описанием.
