# Общий API 0.1.0

**Статус: единый стартовый проектный контракт для обоих промптов, не готовый сервис. До реализации подтвердить его между разработчиками на этапе G0.** Каждый endpoint помечен `x-phase`. Источник схем — `openapi.yaml`; поведенческие правила — этот файл. Если схема и описание расходятся, остановить затронутую реализацию и вынести вопрос, не угадывать.

## Общие правила
Base path `/api/v1`. JSON UTF-8, camelCase, UUID идентификаторы. Названия display-localization отделены от стабильных id. DTO — не JPA entities. `null` означает неизвестно/не задано, а не ноль, false или пустой текст. Клиент терпим к дополнительным полям ответа; неизвестный enum не должен падать — показать безопасное UNKNOWN/unsupported состояние и зафиксировать рассогласование, не отправлять выдуманное значение на сервер.

`page` нумеруется с 0; `size` по умолчанию20, диапазон1..50. Невалидные значения —400. `items,totalElements,totalPages,hasNext,page,size,asOf` имеют одинаковую структуру. Пустая страница имеет items=[]; totalPages=0 только если результатов всего0. Время `asOf` передаёт server clock для статусов, но не превращает demo в реальные данные.

## Public catalogue — B1
GET `/hackathons`: query, format, countryCode, city, topic, registrationStatus, individualApplicationAllowed, startsFrom, startsTo, sort, page,size.
Все заданные фильтры соединяются AND. Search — case-insensitive по title, organizer.name, topic labels и city. Пустой query после trim означает отсутствие поиска. Перечисленные поля — единственно допустимые sort/filter, не передавать сырой SQL.

countryCode/city фильтруют **место проведения**, а не право участвовать из страны. При unknown individualApplicationAllowed=true запись НЕ попадает под фильтр true.

startsFrom/startsTo — включительные localDate границы для `starts.date`, не диапазон пересечения длительных событий. unknown starts не подходит под установленный date filter. from>to —400.

sort по умолчанию DEADLINE_ASC, допустимы START_ASC и NEWEST. Неизвестные даты в конце, стабильный tie-breaker=id. DEADLINE_ASC и START_ASC сравнивают сначала date, затем time при её наличии; для одной даты unknown-time после known-time. Не сочинять время для DATE. Это календарная сортировка по опубликованным локальным датам, а не обещание точной мировой хронологии. NEWEST означает publishedAt DESC,id ASC на сервере (публичное поле publishedAt не обязательно возвращать).

Каталог по умолчанию возвращает published неархивные будущие/идущие события, исключая ENDED и CANCELLED. Прямой GET опубликованного ended/cancelled события и сохранённые ссылки остаются доступными с соответствующим статусом. Draft/archived —404 для public. Hard delete опубликованных событий не делать без решения по сохранённым ссылкам.

GET `/hackathons/{id}` → HackathonDetail. GET `/metadata/filters` → доступные опубликованному каталогу topics/formats/countries/cities и допустимые профильные interests (темы + навыки). Unknown id не придумывать.

## Время и статусы
`EventTime` хранит `precision`, local `date`, nullable `time`,`timeZone`,`instant`. DATE: time/instant=null, zone может быть известна. MINUTE: все три заполнены и согласованы с IANA timezone. В тестах использовать контролируемый Clock. API timestamps технических событий — UTC ISO8601.

Дата проведения, дедлайн заявки и этап сдачи проекта — разные поля. Не заполнять 00:00/23:59 без источника. Для DATE дедлайна не показывать точный countdown. После истечения локальной даты, если timezone известна, можно считать дедлайн прошедшим; в течение даты точный момент неизвестен. При неизвестной timezone не делать точное автоматическое закрытие. Перенос/отмена могут задаваться организаторскими обновлениями вручную.

`registrationStatus` и `eventStatus` — разные состояния. Их вычисление/ручные overrides определить на сервере и покрыть тестами. `OPEN` само по себе не доказывает eligibility для конкретного человека. Приложение читает server status, а не независимо переопределяет его по своим догадкам.

## Призы и правила
totalAmount — строка decimal, Java BigDecimal. Валюта ISO4217. status=UNKNOWN не означает отсутствие призов; NONE означает явно нет приза. В MIXED общий оценочный фонд не подписывать «денежный приз». Не считать общий фонд суммой победителю.

TeamRules отдельно различает подачу индивидуальной заявки, участие соло и формирование команды. allowedCountryCodes=null — неизвестно, [] — явное отсутствие географического ограничения. Online не означает worldwide eligibility.

## Auth — B2
POST `/auth/register` {email,password,displayName} →201 AuthResponse.
POST `/auth/login` {email,password} →200 AuthResponse.
POST `/auth/logout` →204, текущая сессия отзывает доступ.
GET `/me`, PATCH `/me` требуют Authorization: Bearer <opaque token>.

Выбраны **opaque bearer sessions**, НЕ JWT: криптографически случайный токен не менее256 bits entropy; в DB только его SHA-256 hash плюс userId, createdAt, expiresAt, revokedAt. SHA-256 здесь для случайного высокоэнтропийного токена, НЕ для пароля. Configurable TTL, стартовое решение30 дней; expiry абсолютный, без бессрочного silent renewal. Проверять токен и срок при каждом auth request. Refresh endpoint в0.1.0 отсутствует. После expiry —401 и понятное повторное приглашение войти. Не городить несовместимый refresh flow на клиенте.

Пароли — Spring Security PasswordEncoder с настроенным современным adaptive hashing (предпочтительно готовый Argon2id encoder); не самостоятельно написанный алгоритм. Подобрать параметры по окружению, учесть связанные dependencies. Ввод пароля не trim. Лимиты длины и request-size, rate limit по IP+account для auth. Login error общий, не сообщать «email существует». Для выдачи токена Cache-Control:no-store. Никаких токенов, паролей или Authorization в логах.

В API принимается только header Bearer, не URL/body/cookie. Native API filter-chain — без cookie-based auth; CSRF policy выбрать именно для этого chain. Будущая browser admin использует отдельную form/session auth цепочку с CSRF, secure/HttpOnly cookie. Не отключать CSRF глобально. CORS '*' не нужен для native клиента.

Mobile accessToken хранит через SecureTokenStore: iOS Keychain, Android storage с ключом, защищённым Android Keystore; plaintext SharedPreferences/обычные settings запрещены. Не делать доступ к native credentials из общего UI. На logout и account switch очищать account-specific cache; не превращать сохранения аккаунта в гостевые автоматически. Logout на сервере при сети недоступен — локальное стирание не выдавать за подтверждённый отзыв серверной сессии.

Регистрацию в production по умолчанию держать закрытой до anti-abuse/account-lifecycle проверки; private dev signup включать отдельным flag. Public launch требует email verification и password reset (отдельный согласованный contract PR до публичного релиза). Не создавать неработающие кнопки «письмо отправлено».

## Сохранённое и отметки — B2
GET `/me/saved` (page,size) → HackathonPage, порядок savedAt DESC,id ASC. GET включает доступные опубликованные ended/cancelled события с честным статусом. PUT `/me/saved/{id}` →204, DELETE →204; повтор того же действия идемпотентен. Unique(user_id,hackathon_id).

Гость хранит IDs и небольшой snapshot локально. После входа предложить перенос: идемпотентные PUT для каждого ID, подтверждённые успехи записать; не стирать гостевые данные до подтверждения сервера. Нет silent merge между двумя разными аккаунтами. При remote error показывать ошибку/очередь повтора, не переключать в demo.

GET `/me/applications` (page,size) →ApplicationPage. PUT `/me/applications/{id}` →ApplicationMark, DELETE →204. PUT создаёт или возвращает ту же отметку APPLIED, не меняет markedAt при повторе. Порядок GET markedAt DESC,id ASC. selfReported всегдаtrue. Это **не подтверждение организатора**. Клик по external URL не создаёт эту запись автоматически. Закладка и отметка заявки независимы.

PATCH `/me`: отсутствующее поле оставить, явный null очистить только nullable поле. interests — полный новый список id; валидировать допустимые tags, включая профильные skills. Запрещено mass-assign role/admin/email через этот DTO. Профиль других пользователей в MVP не читается.

## Модерация и удаление — B3
POST `/suggestions` и POST `/hackathons/{id}/reports` требуют login, rate limits, длины и server validation; возвращают202 PENDING receipt. Публикация только после admin проверки, не сразу из формы. Не скачивать произвольный URL сервером автоматически (SSRF); сохранять проверенный HTTPS URL, без javascript/file/data и embedded credentials.

POST `/me/delete-account` с password повторно проверяет пароль, отзывает все сессии, удаляет профиль и личные связи;204. Поведение сохранения минимального security audit описать в privacy документе до production.

Admin web `/admin` не часть mobile JSON API: отдельные защищённые form pages с CSRF. Работает с теми же domain services. Доступ USER к админке запрещён независимо от видимости кнопок.

## Ошибки
application/problem+json: type,title,status,detail,instance,code,requestId,fieldErrors. 400 validation,401 auth,403 forbidden,404 notfound,409 conflict,429 rate limit c Retry-After,500 unexpected,503 unavailable. Без stack trace/SQL/token/PII. Mobile UI показывает понятное сообщение, не raw server dump.

## Разработка и внешние ссылки
Dev порт8080. Base URL настраивается окружением. Loopback телефона — не компьютер разработчика. Для типового Android emulator проверить host mapping (часто10.0.2.2), для настоящего телефона — доступный LAN IP компьютера либо HTTPS staging. Для iOS simulator на том же Mac возможен localhost; если backend на другом ПК — LAN/staging. Отдельно проверить firewall и binding. Разрешения HTTP только debug/dev, без отключения TLS certificate validation.

External registration: native browser integration, allow только безопасный HTTPS URL. В DEMO показать предупреждение/preview, не выдавать example.com за регистрацию. Cancelled/closed/no-link состояния не выглядят как активная настоящая запись. Поддержать «Открыть официальный сайт» отдельно от регистрации при необходимости.

## Данные для параллельной разработки
`examples/` — фиксированные вымышленные fixtures с asOf2026-10-12. Мобильный DemoRepository читает их, backend dev seed использует те же IDs/поля. UI-функции не содержат собственные несогласованные моковые ответы. Fixtures — НЕ production seed и не живой каталог.

## Изменение договора
Сначала PR в api + migration notes + fixtures, второй разработчик согласует, только затем оба меняют адаптеры. В backend integration tests сравнивать реальные ответы со схемой. В mobile tests десериализовать те же fixtures. Проверка fixtures не доказывает работоспособность HTTP endpoints.
