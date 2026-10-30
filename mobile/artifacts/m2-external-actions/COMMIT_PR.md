# Дополнительный commit в существующий PR #15

Ветка `feat/mobile-external-actions`; первоначальная реализация уже закоммичена пользователем как `65b8683`, PR создан. Эти команды добавляют исправление IPv6 в ту же ветку. Перед staging проверить отсутствие новых чужих изменений. `.agents/` и `.interface-design/` не добавлять.

```powershell
Set-Location 'C:/Users/user/.codex/worktrees/mobile-catalog-api/HackAtlas'
git branch --show-current
git status --short
git add -- docs/STATUS_MOBILE.md mobile/shared/src/commonMain/kotlin/dev/hackatlas/mobile/domain/ExternalLinks.kt mobile/shared/src/commonTest/kotlin/dev/hackatlas/mobile/domain/ExternalLinksTest.kt mobile/artifacts/m2-external-actions
git diff --cached --check
git diff --cached --stat
git commit -m "fix(mobile): validate IPv6 website addresses"
git push origin feat/mobile-external-actions
```

Новый PR создавать не нужно: push дополнит [PR #15](https://github.com/KDvibers/HackAtlas/pull/15). Его заголовок остаётся **feat(mobile): open registration links and native share**.

Обновлённое описание с 146 тестами и закрытым IPv6-замечанием готово в соседнем `PR_DESCRIPTION.md`. Применить вручную через GitHub или командой (если настроен `gh`):

```powershell
gh pr edit 15 --repo KDvibers/HackAtlas --body-file mobile/artifacts/m2-external-actions/PR_DESCRIPTION.md
```

Проверить правильный GitHub-аккаунт отдельно от автора коммита. Свежий APK `mobile/build/external-actions/HackAtlas-external-actions-ipv6-fixed-debug.apk` находится в ignored build-каталоге и в Git не добавляется. Исполнитель не выполняет commit, push или редактирование PR за пользователя.
