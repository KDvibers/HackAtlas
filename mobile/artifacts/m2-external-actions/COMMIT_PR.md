# Commit и PR выполняет пользователь

Ветка `feat/mobile-external-actions`, база `a4394f50aba09936df5e49234f276976d98e5ff4`. Команды рассчитаны на текущее состояние после итогового ревью. Сначала убедиться, что нет новых чужих изменений. `.agents/` и `.interface-design/` не добавлять.

```powershell
Set-Location 'C:/Users/user/.codex/worktrees/mobile-catalog-api/HackAtlas'
git branch --show-current
git status --short
git add -- docs/STATUS_MOBILE.md docs/superpowers/plans/2026-10-30-mobile-external-actions.md docs/superpowers/specs/2026-10-30-mobile-external-actions-design.md mobile/README.md mobile/TOOLCHAIN.md mobile/shared/src mobile/scripts/android_external_actions_smoke.py mobile/artifacts/m2-external-actions
git diff --cached --check
git diff --cached --stat
git commit -m "feat(mobile): open registration links and native share"
git push -u origin feat/mobile-external-actions
```

Заголовок PR: **feat(mobile): open registration links and native share**.

Описание готово в соседнем `PR_DESCRIPTION.md`. Создание PR после push (если настроен `gh`):

```powershell
gh pr create --base main --head feat/mobile-external-actions --title 'feat(mobile): open registration links and native share' --body-file mobile/artifacts/m2-external-actions/PR_DESCRIPTION.md
```

Либо открыть [сравнение веток](https://github.com/KDvibers/HackAtlas/compare/main...feat/mobile-external-actions), вставить заголовок и текст файла. Проверить правильный GitHub-аккаунт отдельно от автора коммита. APK находится в ignored `mobile/build/external-actions/` и в Git не добавляется.
