# Mobile review сообщения бэкендчика — 1 ноября 2026

Источник: присланный `message (18).txt`, совпадает по смыслу с draft PR22
`docs/proposals/account-lifecycle/README.md`. Это входной документ для review,
не поручение менять API и не подтверждение готовности endpoint/SMTP.
В этом mobile-этапе используется только действующий Starter.

## Оценка с мобильной стороны

1. **Pending opaque Bearer — разумный кандидат.** Он сохраняет нынешние envelope и
   native secure storage. Но одной добавки emailVerified недостаточно: нужен
   отдельный PendingVerification в UI/capabilities, запрет account read/write/import,
   проверка offline cache/foreground/logout и переход в verified после GET me.
   Альтернатива email-first сильнее меняет уже проверенную регистрацию.
2. **Коды вместо deep links подходят текущему стеку**, отдельные текстовые поля и
   paste, ведущие нули сохраняются строкой. Сроки/лимиты предложены сервером; на
   клиенте countdown/resend — UX, окончательное решение всегда серверу. Reset-only
   grant не сохранять как Bearer и не отправлять к /me.
3. **Три403/400сценария должны различаться по Problem.code.** Текущий auth client
   хранит только HTTP status/Retry-After. EMAIL_NOT_VERIFIED не должен очищать
   сессию, EMAIL_ACTION_INVALID не вызывает logout, PASSWORD_RESET_REQUIRED из
   login не выдаёт Bearer. Это потребует отдельного typed error mapping и tests,
   не исправляется сменой текста общего403.
4. **Forced recovery после backup и отзыв всех сессий** — согласуемое серверное
   поведение; мобильному UI нужен явный reset-required путь. Нельзя обещать его
   готовность до backend/runtime/mail проверки. Legacy accounts/доставочный адрес/
   регистр email и rollout нужно решить до включения guard.
5. **Best-effort delivery** можно рассматривать для private dev при честном202,
   resend/monitoring; оно теряет задания при restart. Требование надёжной доставки
   для реального выпуска нужно отдельно решить с backend, не добавлять durable
   scope или выбирать SMTP-провайдера молча.

## Что согласовать до изменения canonical API

- Обязательный emailVerified и новые Problem.code затрагивают закрытые схемы;
  старый клиент не считать автоматически совместимым. Согласовать порядок
  обновления backend/mobile и поведение старых приложений/существующих аккаунтов.
- Pending cached data: не показывать account saved и не импортировать guest после
  получения pending state; что делать с прежним cache — явно закрепить в семантике.
- Для public reset использовать изолированный HTTP flow без Bearer/cookies, не
  копировать session interception. Обычный login после reset и очистка private state.
- Не писать «письмо отправлено» по202; формулировка «запрос принят» до фактической
  доставки. Нужны реальные почтовые/device smoke отдельно от schema/MockEngine.

## Готовый текст другу (не отправлен инструментами)

> Привет! Посмотрел draft с mobile-стороны. Предпочтительный вариант для обсуждения —
> pending opaque Bearer, коды с сохранением ведущих нулей и отдельный reset-only grant;
> это ближе к текущему клиенту. Но пока считаю это предложением, не утверждённым API.
> Понадобятся emailVerified/PendingVerification, ограничения account saved/import,
> отдельная обработка EMAIL_NOT_VERIFIED / EMAIL_ACTION_INVALID /
> PASSWORD_RESET_REQUIRED и изолированный reset flow без Bearer. Перед contract PR
> давай закрепим rollout для старых клиентов/аккаунтов, поведение private cache в
> pending и forced recovery после backup. Best-effort SMTP допускает потерю письма
> при restart: для dev можно обсуждать resend/monitoring, требования выпуска решим
> отдельно. Сейчас я делаю только редактирование профиля по действующему PATCH /me;
> verification/reset endpoint не подключаю и готовыми их не считаю.

Текст — рекомендация исполнителя, не одобрение draft от имени пользователя.
