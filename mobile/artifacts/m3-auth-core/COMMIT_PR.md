# Пользовательские commit / push / PR

Ветка `feat/mobile-auth-core` от main `92ca815d4c12249cbff829fc3f62b1280bde866d`. Перед staging проверить отсутствие новых чужих правок. `.agents/` и `.interface-design/` не добавлять.

```powershell
Set-Location 'C:/Users/user/.codex/worktrees/mobile-catalog-api/HackAtlas'
git branch --show-current
git status --short
git add -- docs/STATUS_MOBILE.md docs/superpowers/specs/2026-10-31-mobile-auth-core-design.md docs/superpowers/plans/2026-10-31-mobile-auth-core.md mobile/README.md mobile/AUTH_CORE.md mobile/shared/build.gradle.kts mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/data/auth mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/data/auth mobile/shared/src/androidMain/kotlin/dev/hackatlas/mobile/data/auth mobile/shared/src/iosMain/kotlin/dev/hackatlas/mobile/data/auth mobile/shared/src/androidInstrumentedTest mobile/artifacts/m3-auth-core
git diff --cached --check
git diff --cached --stat
git commit -m "feat(mobile): add secure opaque session client"
git push -u origin feat/mobile-auth-core
gh pr create --repo KDvibers/HackAtlas --base main --head feat/mobile-auth-core --title 'feat(mobile): add secure opaque session client' --body-file mobile/artifacts/m3-auth-core/PR_DESCRIPTION.md
```

Либо после push открыть [сравнение веток](https://github.com/KDvibers/HackAtlas/compare/main...feat/mobile-auth-core) и вставить PR_DESCRIPTION.md. Исполнитель не выполняет commit, push, создание PR или merge. Не добавлять target/build, настоящие .env и credentials.
