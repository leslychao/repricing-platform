<!doctype html>
<html lang="ru"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Вход — Repricer</title><link rel="stylesheet" href="${url.resourcesPath}/css/login.css"></head>
<body><main class="error-page"><a class="brand" href="/login"><span class="brand-mark">R</span>repricer</a><h1>Не удалось завершить вход</h1><#if message?has_content><p class="alert" role="alert">${kcSanitize(message.summary)?no_esc}</p></#if><a class="primary" href="/login">Вернуться ко входу</a></main></body></html>
