# Ресурсы HackAtlas M1

Дата: 2026-10-12. Визуальный эталон остаётся `design/reference/approved-reference.png`.
Все новые ресурсы ниже — **кандидаты M1, ожидающие визуального подтверждения**. Они не являются извлечёнными исходниками референса. Точное название его исходного шрифта неизвестно.

## Шрифты

| Роль | Семейство / app-файл | Источник | Лицензия и исходник |
|---|---|---|---|
| Пиксельные заголовки | Press Start 2P Regular / `press_start_2p.ttf` | [Google Fonts](https://github.com/google/fonts/tree/main/ofl/pressstart2p) | SIL OFL 1.1; `licenses/pressstart2p-OFL.txt`; оригинальный TTF в `fonts/sources/` |
| Основной текст | Inter 400 / 600 / 700 / `inter_regular.ttf`, `inter_semibold.ttf`, `inter_bold.ttf` | [Google Fonts](https://github.com/google/fonts/tree/main/ofl/inter) | SIL OFL 1.1; `licenses/inter-OFL.txt`; оригинальный variable TTF в `fonts/sources/` |
| Названия событий | Exo 2 Bold / `exo_2_bold.ttf` | [Google Fonts](https://github.com/google/fonts/tree/main/ofl/exo2) | SIL OFL 1.1; `licenses/exo2-OFL.txt`; оригинальный variable TTF в `fonts/sources/` |
| Техно-подписи | JetBrains Mono Regular / `jetbrains_mono_regular.ttf` | [JetBrains](https://github.com/JetBrains/JetBrainsMono/tree/master/fonts/ttf) | SIL OFL 1.1; `licenses/jetbrainsmono-OFL.txt`; оригинальный TTF в `fonts/sources/` |

Лицензии также включены в `mobile/shared/src/commonMain/composeResources/files/licenses/` для распространения вместе с приложением.

Inter и Exo 2 преобразованы fontTools в статические экземпляры; контуры не редактировались. Внутренние имена производных — **HackAtlas Body** и **HackAtlas Event**, чтобы явно отличать их от оригиналов. Для Inter зафиксирован `opsz=14`; веса 400/600/700. Для Exo 2 вес 700. Press Start 2P и JetBrains Mono скопированы без изменения байтов. Все app-файлы размещены в `mobile/shared/src/commonMain/composeResources/font/`.

`fonts/prepare_fonts.py` воспроизводит статические экземпляры, проверяет SHA-256 оригиналов и cmap, создаёт `fonts/FONT_AUDIT.json` и реальный типографический образец `fonts/typography-specimen.png`. Зависимости: Python, fontTools, Pillow. Введённые в скрипт контрольные суммы предотвращают незаметную подмену файла при изменении upstream. При наличии исходных TTF и лицензий сеть не нужна.

Проверены: весь русский алфавит, «Ё/ё», «Й», «Хакатоны», «Профиль», «Сохранённое», латиница и цифры — без пропущенных glyphs во всех шести app-файлах. В образце Pillow каждому тексту задан конкретный font-файл; это не скриншот приложения. Доступность glyph в cmap не заменяет проверку рендера Android/iOS.

Валюты `₽ ₸ € $` присутствуют в Press Start 2P и Inter. **В Exo 2 и JetBrains Mono отсутствует `₸`**, поэтому денежные суммы должны использовать body Inter. Не допускать незаметный системный fallback в PixelHeading; неподдерживаемый динамический текст требует явного решения, а не подмены всей гарнитуры.

## Иллюстрации

| Файл | Назначение | Источник / разрешение | Исходник |
|---|---|---|---|
| `illustrations/ai_head.png` | AI-карточка и detail hero | Сгенерирован встроенным OpenAI image_gen по разрешению пользователя самостоятельно подобрать/подготовить ассеты | Сохранён исходный PNG; редактируемой 3D-сцены нет |
| `illustrations/fintech_skyline.png` | Финтех-карточка | То же | Сохранён исходный PNG |
| `illustrations/game_controller.png` | Game jam карточка | То же | Сохранён исходный PNG |
| `illustrations/demo_avatar.png` | Только явно обозначенный DEMO-профиль | То же | Сохранён исходный PNG |

Четыре независимых вызова **встроенного image_gen**, без CLI/API-key. Финальные промпты сохранены в `GENERATION_PROMPTS.json`. Сторонняя лицензия на эти новые изображения не заявляется; это сгенерированные ассеты проекта, не извлечённые логотипы или чужие фотографии. Пользовательская лицензия всего репозитория этим файлом не устанавливается.

Каждый PNG имеет 1254×1254 пикселя, RGBA и реальную прозрачность (alpha 0…255). Байтовые копии находятся в `mobile/shared/src/commonMain/composeResources/drawable/`. Проверка параметров и совпадения копий — `ILLUSTRATION_AUDIT.json`. Рендерить с `ContentScale.Fit` на тёмной поверхности, без растяжения. Визуально проверены отдельные изображения: узнаваемые силуэты, нет текста/UI/водяных знаков. Новый арт ярче и объёмнее тонкой графики референса; окончательные размер и opacity нужно оценить в реальных скриншотах.

## Иконки и токены

`OutlineIcon.kt` — оригинальный небольшой контурный набор, рисуемый Compose Canvas в координатах 24×24. Не шрифтовые символы и не emoji; сторонних icon files нет. Интерактивные области и названия действий задаются компонентом-кнопкой, содержащим иконку.

`HackAtlasTheme.kt` использует стартовые цвета и радиусы из `design/tokens.json`. Цвета токенов не менялись; display-размер 26sp выбран для читаемого заголовка на 360–430dp и остаётся кандидатом до визуальной приёмки. Бесконечных анимаций нет. Экранные компоненты и навигация находятся вне этого ресурсного пакета.
