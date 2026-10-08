<!doctype html>
<html lang="ru">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width,initial-scale=1">
  <title>Вход — Repricer</title>
  <link rel="stylesheet" href="${url.resourcesPath}/css/login.css">
</head>
<body>
<main class="auth">
  <section class="auth-art" aria-label="Repricer">
    <a class="brand" href="/login"><span class="brand-mark" aria-hidden="true">R</span>repricer</a>
    <div class="auth-story">
      <span class="brand-mark large" aria-hidden="true">R</span>
      <h2>Вернитесь к работе.<br>Всё важное уже здесь.</h2>
      <p>Товары, правила и экономика вашего кабинета в одном спокойном рабочем пространстве.</p>
      <div class="auth-points">
        <div><span aria-hidden="true">✓</span>Понятные предложения цены</div>
        <div><span aria-hidden="true">✓</span>Проверки перед применением</div>
        <div><span aria-hidden="true">✓</span>История решений и изменений</div>
      </div>
    </div>
    <footer>repricer · Рабочее пространство продавца</footer>
  </section>
  <section class="auth-form-wrap">
    <form class="auth-form" action="${url.loginAction}" method="post">
      <h1>Вход в аккаунт</h1>
      <p class="description">Продолжите работу в своём пространстве.</p>
      <#if message?has_content><div class="alert" role="alert">${kcSanitize(message.summary)?no_esc}</div></#if>
      <div class="field">
        <label for="username">Email или логин</label>
        <input id="username" name="username" value="${(login.username!'')}" autocomplete="username" placeholder="name@company.ru" autofocus required>
      </div>
      <div class="field">
        <label for="password">Пароль</label>
        <input id="password" name="password" type="password" autocomplete="current-password" placeholder="Введите пароль" required>
      </div>
      <input type="hidden" name="credentialId" value="${(auth.selectedCredential!'')}">
      <p class="auth-extra">Защищённый вход</p>
      <button class="primary" type="submit">Войти <span aria-hidden="true">→</span></button>
      <p class="auth-note">Доступ предоставляется компанией.<br>Если войти не получается, обратитесь к её владельцу.</p>
    </form>
  </section>
</main>
</body>
</html>
