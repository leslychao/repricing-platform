package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.access.LogoutService;
import ru.oritas.repricer.platform.BusinessException;

class LogoutServiceTest {
  private HttpServer server;
  private LogoutService logout;
  private final AtomicInteger keycloakCalls = new AtomicInteger();
  private final AtomicInteger proxyCalls = new AtomicInteger();
  private int keycloakStatus = 204;
  private int proxyStatus = 302;
  private boolean clearCookie = true;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/keycloak/logout", this::endKeycloakSession);
    server.createContext("/oauth2/sign_out", this::endProxySession);
    server.start();
    URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    logout = new LogoutService(base.resolve("/keycloak/logout"), base);
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  @Test
  void successRequiresKeycloakThenProxyAndReturnsOnlyCookieDeletionHeaders() {
    assertEquals(
        List.of("session=; Path=/; Max-Age=0; HttpOnly"),
        logout.terminate("synthetic-id-token", "session=synthetic-ticket"));
    assertEquals(1, keycloakCalls.get());
    assertEquals(1, proxyCalls.get());
  }

  @Test
  void unconfirmedKeycloakLogoutKeepsTheAppSessionForAnExplicitRetry() {
    keycloakStatus = 503;
    assertThrows(
        BusinessException.class,
        () -> logout.terminate("synthetic-id-token", "session=synthetic-ticket"));
    assertEquals(1, keycloakCalls.get());
    assertEquals(0, proxyCalls.get());
  }

  @Test
  void proxyFailureCannotBeReportedAsAFullLogout() {
    proxyStatus = 503;
    assertThrows(
        BusinessException.class,
        () -> logout.terminate("synthetic-id-token", "session=synthetic-ticket"));
    assertEquals(1, keycloakCalls.get());
    assertEquals(1, proxyCalls.get());
  }

  @Test
  void redirectWithoutClearingTheSessionCookieIsUnconfirmed() {
    clearCookie = false;
    assertThrows(
        BusinessException.class,
        () -> logout.terminate("synthetic-id-token", "session=synthetic-ticket"));
  }

  private void endKeycloakSession(HttpExchange exchange) throws IOException {
    keycloakCalls.incrementAndGet();
    assertEquals("POST", exchange.getRequestMethod());
    assertNull(exchange.getRequestURI().getQuery());
    assertEquals(
        "id_token_hint=synthetic-id-token",
        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    exchange.sendResponseHeaders(keycloakStatus, -1);
    exchange.close();
  }

  private void endProxySession(HttpExchange exchange) throws IOException {
    proxyCalls.incrementAndGet();
    assertEquals(1, keycloakCalls.get());
    assertEquals("session=synthetic-ticket", exchange.getRequestHeaders().getFirst("Cookie"));
    if (clearCookie) {
      exchange.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0; HttpOnly");
    }
    exchange.sendResponseHeaders(proxyStatus, -1);
    exchange.close();
  }
}
