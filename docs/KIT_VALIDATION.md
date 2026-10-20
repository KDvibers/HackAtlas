# Проверка перед выдачей комплекта

Проверено локально: JSON Schema всех моделей, локальные OpenAPI `$ref`, уникальность operationId, обязательные path parameters, шесть JSON fixtures, относительные Markdown-ссылки, отсутствие font binaries и совпадение референса с последним загруженным изображением.

- PASS catalog-page.json -> HackathonPage
- PASS catalog-empty.json -> HackathonPage
- PASS hackathon-detail.json -> HackathonDetail
- PASS profile.json -> Profile
- PASS filter-metadata.json -> FilterMetadata
- PASS validation-error.json -> Problem

Reference SHA256: `fd7dd0a85b8dd39a278481cc43faf96199f8ad098fbad1e885be6ab75c2be1cf`.

Не проверено и не реализовано: Android/iOS приложение, backend endpoints, PostgreSQL, CI, GitHub repository, Figma. Полный специализированный OpenAPI validator не запускался; проведены перечисленные структурные/JSON Schema проверки.
