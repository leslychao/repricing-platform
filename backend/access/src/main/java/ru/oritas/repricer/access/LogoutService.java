package ru.oritas.repricer.access;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.platform.BusinessException;

/** Full logout is successful only after both server-side sessions acknowledge termination. */
@Service
public final class LogoutService {
  private final HttpClient http =
      HttpClient.newBuilder()
          .followRedirects(HttpClient.Redirect.NEVER)
          .connectTimeout(Duration.ofSeconds(5))
          .build();
  private final URI keycloak;
  private final URI proxy;

  public LogoutService(
      @Value("${repricer.oidc.logout-url}") URI keycloak,
      @Value("${repricer.oauth2-proxy-url}") URI proxy) {
    this.keycloak = keycloak;
    this.proxy = proxy;
  }

  public List<String> terminate(String verifiedIdToken, String cookie) {
    if (verifiedIdToken == null
        || verifiedIdToken.length() > 16384
        || cookie == null
        || cookie.length() > 16384
        || cookie.contains("\r")
        || cookie.contains("\n")) {
      throw failed();
    }
    try {
      HttpResponse<Void> keycloakResult =
          http.send(
              HttpRequest.newBuilder(keycloak)
                  .timeout(Duration.ofSeconds(15))
                  .header("Content-Type", "application/x-www-form-urlencoded")
                  .POST(
                      HttpRequest.BodyPublishers.ofString(
                          "id_token_hint="
                              + URLEncoder.encode(verifiedIdToken, StandardCharsets.UTF_8)))
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      if (keycloakResult.statusCode() != 204 && keycloakResult.statusCode() != 200) {
        throw failed();
      }
      HttpResponse<Void> proxyResult =
          http.send(
              HttpRequest.newBuilder(proxy.resolve("/oauth2/sign_out?rd=%2F"))
                  .timeout(Duration.ofSeconds(15))
                  .header("Cookie", cookie)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.discarding());
      List<String> cleared = proxyResult.headers().allValues("Set-Cookie");
      if (proxyResult.statusCode() != 302 || cleared.isEmpty()) {
        throw failed();
      }
      return List.copyOf(cleared);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw failed();
    } catch (IOException exception) {
      throw failed();
    }
  }

  private static BusinessException failed() {
    return new BusinessException(
        "LOGOUT_UNCONFIRMED",
        503,
        "Полный выход не подтверждён. Повторите выход, когда соединение восстановится");
  }
}
