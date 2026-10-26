# Команды для пользователя

Task5 оставлена без staging/commit/push. Выполнять в PowerShell из этой рабочей копии. `.agents/`, `.interface-design/`, `.env`, SDK, APK и build/logs в список не входят.

```powershell
Set-Location 'C:\Users\user\.codex\worktrees\mobile-catalog-api\HackAtlas'
git branch --show-current
git status --short
```

Ожидаемая ветка: `feat/mobile-catalog-api`. Добавить только файлы Task5:

```powershell
$task5Paths = @(
  'docs/STATUS_MOBILE.md'
  'docs/superpowers/plans/2026-10-25-mobile-catalog-api.md'
  'mobile/README.md'
  'mobile/TOOLCHAIN.md'
  'mobile/androidApp/build.gradle.kts'
  'mobile/androidApp/src/main/kotlin/dev/hackatlas/mobile/HackAtlasApplication.kt'
  'mobile/shared/src/androidMain/kotlin/dev/hackatlas/mobile/data/network/PlatformHttpEngine.android.kt'
  'mobile/shared/src/androidUnitTest/kotlin/dev/hackatlas/mobile/data/network/PlatformHttpEngineTest.kt'
  'mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/AppState.kt'
  'mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/CatalogRuntime.kt'
  'mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/CatalogStore.kt'
  'mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/ui/CatalogueScreen.kt'
  'mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/data/OfflineBootstrapTest.kt'
  'mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/ui/CatalogStoreTest.kt'
  'mobile/scripts/android_remote_smoke.py'
  'mobile/scripts/test_android_remote_smoke.py'
  'mobile/artifacts/m2-remote'
)
git add -- $task5Paths
git diff --cached --check
git diff --cached --stat
git commit -m "feat(mobile): connect public catalog API"
git push -u origin feat/mobile-catalog-api
```

Открыть [сравнение main...feat/mobile-catalog-api](https://github.com/KDvibers/HackAtlas/compare/main...feat/mobile-catalog-api?expand=1). Если для этой ветки уже есть открытый PR, push обновит его; дубликат не нужен. Иначе base=`main`, compare=`feat/mobile-catalog-api`.

Заголовок: **feat(mobile): connect public catalog API with offline guest snapshots**.

В описание вставить содержимое [PR_DESCRIPTION.md](PR_DESCRIPTION.md). Ссылки станут доступны после push этой ветки. Галерею `review.html` смотреть локально; GitHub показывает HTML как исходник.

Если предпочитаешь GitHub CLI и PR ещё не создан:

```powershell
gh pr create --base main --head feat/mobile-catalog-api --title "feat(mobile): connect public catalog API with offline guest snapshots" --body-file mobile/artifacts/m2-remote/PR_DESCRIPTION.md
```

Проверять diff и выполнять merge с напарником после проверки GitHub CI. Эти команды агент не выполнял.
