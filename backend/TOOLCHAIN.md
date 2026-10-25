# Backend toolchain — B0/B1/B2/B3

Фиксация от 20 октября 2026. Версии зависимостей определяются [pom.xml](pom.xml) и Spring Boot BOM; документация объясняет выбор и не заменяет manifests.

| Компонент | Зафиксировано |
|---|---|
| Java | Language / bytecode target 21; локально проверен JDK 21.0.12.1 |
| Spring Boot parent / BOM | 4.1.1 |
| Spring Security | 7.1.1 через Boot BOM, подключён B2 |
| Thymeleaf | 3.1.5.RELEASE через Boot BOM, server-side HTML админка B3 |
| Bouncy Castle | `bcprov-jdk18on:1.86`, нужен Spring Argon2id encoder |
| Maven | 3.9.16 |
| Maven Wrapper | 3.3.4, `only-script`, без wrapper JAR в Git |
| PostgreSQL | 17.11, официальный `bookworm` image, digest ниже |
| Flyway | 12.4.0 через Boot BOM |
| PostgreSQL JDBC | 42.7.13 через Boot BOM |
| Testcontainers | 2.0.5 через Boot BOM |
| Maven Surefire / Failsafe | 3.5.6 через Boot parent |
| Bruno CLI | 4.2.1, npm lock в `tools/bruno/` |
| Schemathesis | 4.32.2, полный Python lock в `scripts/b1/requirements.txt` |
| Node.js для Bruno | 24.19.0, отдельный инструмент проверки, не серверный runtime |

Используются Spring MVC, Spring Data JPA, Actuator и Flyway starters. B1 добавляет публичный каталог без новых Java dependencies. B2 подключает Spring Security, Validation (`@Email`) и Bouncy Castle для Argon2id; параметры и ограничения описаны в [AUTH](AUTH.md). Security/Validation согласованы Boot BOM; версия Bouncy Castle закреплена явно после проверки Maven Central metadata и сборки с Java 21. `0.1.0-SNAPSHOT` — версия собственного разрабатываемого приложения, а не предварительная версия сторонних dependencies.

Mockito из `spring-boot-starter-test` запускается явным Java agent в Surefire/Failsafe по [официальной инструкции для Java 21+](https://javadoc.io/static/org.mockito/mockito-core/5.24.0/org.mockito/org/mockito/Mockito.html#0.3). Maven dependency plugin разрешает путь к JAR; late `argLine` сохраняет совместимость с будущими test agents. Это устраняет динамическое self-attach при старте Spring test context; тесты и listeners не отключаются.

PostgreSQL digest: `sha256:3645570cccdfa447589da9f57dd740faa29b30938e861289a5574b6ca6b03826`. Он используется и для Compose, и для интеграционных тестов; тесты не зависят от состояния dev volume. Exact versions и hashes CI Actions / Gitleaks собраны в [infra/README](../infra/README.md).

B3 добавляет только `spring-boot-starter-thymeleaf` из существующего Boot BOM. HTML формируется на сервере, дополнительный JavaScript runtime или сборщик UI не нужен. Spring Security сохраняет opaque Bearer API и отдельно защищает admin browser session/CSRF. Для модерации переиспользуется проверенный алгоритм ограниченных окон B2 с отдельными счётчиками: Bucket4j и Redis не добавлялись. PostgreSQL row locks, FK и транзакции обеспечивают атомарное удаление аккаунта и аудит. Подробности — [MODERATION](MODERATION.md).

## Wrapper и проверка загрузок

[maven-wrapper.properties](.mvn/wrapper/maven-wrapper.properties) закрепляет HTTPS URL Maven Central и SHA-256 Maven ZIP:

```text
5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce
```

При подготовке официальный Wrapper ZIP 3.3.4 проверен PGP-подписью Apache KEYS; его SHA-256 — `6cb584c2bc907b849a0b931d8266d3ff3214cdd3127115ed4f49fb7176413d36`. Maven ZIP дополнительно сверен с опубликованным Apache SHA-512. Wrapper проверяет закреплённый SHA-256 при загрузке Maven.

Unix `mvnw` совпадает с официальным скриптом. В `mvnw.cmd` внесён минимальный guard вокруг определения symlink target `MAVEN_USER_HOME`: в Windows PowerShell `Get-Item.Target` может быть null. Это известная [upstream issue #395](https://github.com/apache/maven-wrapper/issues/395). Проверка использует `-LiteralPath -Force`, безопасно получает первый Target и сохраняет исходный путь, если Target пуст. URL, проверка checksum и исполнение Maven не изменены. При обновлении Wrapper сначала проверить, исправлена ли проблема upstream.

Нужен `unzip` в Unix: иначе upstream Wrapper выбирает TAR fallback, а закреплённая checksum относится к ZIP. Не удалять проверку checksum ради обхода этой зависимости.

## Фактически проверенное окружение

- Windows: Eclipse Temurin 21.0.12.1; `mvnw.cmd --version` с отдельным пустым кешем успешно загрузил Maven 3.9.16.
- Ubuntu WSL2: OpenJDK 21.0.12.1, Docker Engine 29.1.3, Compose 2.40.3; Unix Wrapper с пустым кешем успешно загрузил Maven 3.9.16.
- Полная сборка и PostgreSQL integration tests выполняются через WSL. Наличие Java/Maven в Windows не означает проверку Windows-доступа к Docker.
- CI задаёт поддерживаемую Java 21 Temurin; patch JDK обновляется setup-java в пределах 21. Перед изменением major или Boot BOM нужны новая сборка и тесты.

Из `backend/` в PowerShell:

```powershell
.\mvnw.cmd --version
.\mvnw.cmd --batch-mode --no-transfer-progress verify
```

Из `backend/` в bash:

```bash
sh ./mvnw --version
sh ./mvnw --batch-mode --no-transfer-progress verify
```

Docker должен быть доступен процессу JVM, запускающему tests. Проверенный вызов через WSL из Windows и команды dev-запуска — в [README](README.md), фактические результаты — в [STATUS_BACKEND](../docs/STATUS_BACKEND.md).

Bruno и Schemathesis проверяют собранный JAR в отдельном Linux-окружении с временной PostgreSQL. CI использует Python 3.12, локальный B1 runner — Python 3.14; существующий offline validator также проверен на Windows Python 3.12. Точные команды, pins, источники и границы генеративных проверок — в [scripts/b1/README](../scripts/b1/README.md). Глобальные CLI и платные функции GUI не требуются.

## Первичные источники

- [Spring Boot requirements](https://docs.spring.io/spring-boot/4.1/system-requirements.html) и [точный BOM 4.1.1](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom).
- [Maven releases](https://maven.apache.org/download.cgi), [Wrapper 3.3.4](https://maven.apache.org/tools/wrapper/download.cgi) и [проверка дистрибутива Wrapper](https://maven.apache.org/tools/wrapper/index.html).
- [Spring external configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html): extension hint `[.properties]` для локального `.env` и приоритет переменных окружения.
- [Actuator endpoints](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html) и [configuration properties](https://docs.spring.io/spring-boot/appendix/application-properties/index.html): явное разрешение только health и отключение деталей ошибок.

Обновления зависимостей делаются отдельным небольшим изменением: проверить поддерживаемую stable-версию, обновить связанные pins и выполнить `verify`, contract check и secret scan. Не менять независимо transitive dependencies Boot BOM без обоснования.
