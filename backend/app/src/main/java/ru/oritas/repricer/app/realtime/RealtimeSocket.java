package ru.oritas.repricer.app.realtime;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import ru.oritas.repricer.access.IdentityService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Scope;

/** One authenticated transport with independently authorized organization/account subscriptions. */
@Component
public final class RealtimeSocket extends TextWebSocketHandler {
  private static final CloseStatus ACCESS_CHANGED = new CloseStatus(4003, "Access changed");
  private static final Logger log = LoggerFactory.getLogger(RealtimeSocket.class);
  private final ScopeTransactionRunner transactions;
  private final RealtimeEventGateway events;
  private final JsonCodec json;
  private final Clock clock;
  private final Map<String, Connection> connections = new ConcurrentHashMap<>();
  private final AtomicLong pendingBytes = new AtomicLong();
  private static final long MAX_PENDING_BYTES = 64L * 1024 * 1024;

  public RealtimeSocket(ScopeTransactionRunner transactions, RealtimeEventGateway events,
      JsonCodec json, Clock clock) {
    this.transactions = transactions;
    this.events = events;
    this.json = json;
    this.clock = clock;
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws IOException {
    session.setTextMessageSizeLimit(8192);
    session.setBinaryMessageSizeLimit(0);
    if (!(session.getPrincipal() instanceof JwtAuthenticationToken authentication)) {
      close(session, ACCESS_CHANGED);
      return;
    }
    var token = authentication.getToken();
    if (token.getExpiresAt() == null || !clock.instant().isBefore(token.getExpiresAt())) {
      close(session, ACCESS_CHANGED);
      return;
    }
    UUID subject = IdentityService.userId(token.getIssuer().toString(), token.getSubject());
    synchronized (connections) {
      long userConnections = connections.values().stream()
          .filter(connection -> connection.subject.equals(subject)).count();
      if (connections.size() >= 1000 || userConnections >= 5) {
        session.close(CloseStatus.SERVICE_OVERLOAD);
        return;
      }
      connections.put(session.getId(), new Connection(
          new ConcurrentWebSocketSessionDecorator(session, 10000, 1024 * 1024), subject,
          token.getExpiresAt(), clock.instant()));
    }
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
    if (!(session.getPrincipal() instanceof JwtAuthenticationToken authentication)) {
      close(session, ACCESS_CHANGED);
      return;
    }
    Connection connection = connections.get(session.getId());
    if (connection == null) {
      close(session, ACCESS_CHANGED);
      return;
    }
    int messageBytes = message.getPayload().getBytes(StandardCharsets.UTF_8).length;
    synchronized (connection) {
      long second = clock.instant().getEpochSecond();
      if (second != connection.controlSecond) {
        connection.controlSecond = second;
        connection.controlCount = 0;
      }
      if (messageBytes > 8192 || ++connection.controlCount > 10) {
        close(connection.session, CloseStatus.SERVICE_RESTARTED);
        return;
      }
    }
    Subscribe request;
    try {
      request = json.decode(message.getPayload(), Subscribe.class);
    } catch (BusinessException | IllegalArgumentException exception) {
      close(session, CloseStatus.BAD_DATA);
      return;
    }
    if (request == null || request.subscriptionId() == null
        || request.subscriptionId().isBlank() || request.subscriptionId().length() > 100) {
      close(session, CloseStatus.BAD_DATA);
      return;
    }
    if ("unsubscribe".equals(request.type())) {
      synchronized (connection) {
        connection.subscriptions.remove(request.subscriptionId());
        removeUnusedScopes(connection);
      }
      return;
    }
    if (!"subscribe".equals(request.type()) || request.organizationId() == null
        || request.resources() == null || request.resources().isEmpty()
        || request.resources().size() > 64 || request.resources().contains(null)) {
      close(session, CloseStatus.BAD_DATA);
      return;
    }
    var token = authentication.getToken();
    if (token.getExpiresAt() == null || !clock.instant().isBefore(token.getExpiresAt())) {
      close(session, ACCESS_CHANGED);
      return;
    }
    UUID subject = IdentityService.userId(token.getIssuer().toString(), token.getSubject());
    Scope scope = new Scope(request.organizationId(), request.accountId(), subject);
    synchronized (connection) {
      int metadataBytes = connection.subscriptions.entrySet().stream()
          .filter(entry -> !entry.getKey().equals(request.subscriptionId()))
          .mapToInt(entry -> entry.getValue().metadataBytes()).sum() + messageBytes;
      if (!subject.equals(connection.subject)
          || (!connection.subscriptions.containsKey(request.subscriptionId())
              && connection.subscriptions.size() >= 32) || metadataBytes > 65536) {
        close(connection.session, CloseStatus.SERVICE_RESTARTED);
        return;
      }
      try {
        var start = transactions.run(scope, () -> events.subscribe(scope, request.resources()));
        ScopeCursor existing = connection.scopes.get(scope);
        if (existing != null && !existing.permissions.equals(start.permissions())) {
          close(connection.session, ACCESS_CHANGED);
          return;
        }
        connection.scopes.putIfAbsent(scope, new ScopeCursor(start.cursor(), start.permissions()));
        connection.subscriptions.put(request.subscriptionId(),
            new Subscription(scope, Set.copyOf(request.resources()), messageBytes));
        removeUnusedScopes(connection);
        // Cursor precedes the ACK and REST load; concurrent changes remain deliverable.
        sendMessage(connection, new Acknowledged("subscribed", request.subscriptionId()));
      } catch (BusinessException exception) {
        close(connection.session, exception.status() == 403 || exception.status() == 401
            ? ACCESS_CHANGED : CloseStatus.BAD_DATA);
      }
    }
  }

  @Scheduled(fixedDelay = 1000)
  public void deliver() {
    for (Connection connection : connections.values()) {
      synchronized (connection) {
        try {
          if (!connection.session.isOpen() || !clock.instant().isBefore(connection.expiresAt)) {
            close(connection.session, ACCESS_CHANGED);
            continue;
          }
          Instant now = clock.instant();
          if (connection.lastPong.plusSeconds(60).isBefore(now)) {
            close(connection.session, CloseStatus.SESSION_NOT_RELIABLE);
            continue;
          }
          if (!connection.lastPing.plusSeconds(20).isAfter(now)) {
            connection.session.sendMessage(new PingMessage());
            connection.lastPing = now;
          }
          for (var entry : connection.scopes.entrySet()) {
            Scope scope = entry.getKey();
            ScopeCursor cursor = entry.getValue();
            Set<String> requested = new HashSet<>();
            for (Subscription subscription : connection.subscriptions.values()) {
              if (subscription.scope().equals(scope)) {
                requested.addAll(subscription.resources());
              }
            }
            var changes = transactions.run(scope,
                () -> events.deliver(scope, requested, cursor.cursor, cursor.permissions));
            if (changes.size() > 32) {
              close(connection.session, CloseStatus.SERVICE_RESTARTED);
              break;
            }
            for (var change : changes) {
              send(connection, scope, change.resource(), change.sequence(), change.entityId());
              if (requested.contains("notifications")) {
                send(connection, scope, "notifications", change.sequence(), change.id());
              }
              cursor.cursor = change.sequence();
            }
          }
        } catch (BusinessException exception) {
          closeQuietly(connection.session, exception.status() == 403 || exception.status() == 401
              ? ACCESS_CHANGED : CloseStatus.SERVER_ERROR);
        } catch (Exception exception) {
          // Storage/transport failures do not revoke the user's selected access scope.
          log.warn("Realtime delivery failed: {}", exception.getClass().getSimpleName());
          closeQuietly(connection.session, CloseStatus.SERVER_ERROR);
        }
      }
    }
  }

  @Override
  protected void handlePongMessage(WebSocketSession session, PongMessage message) {
    Connection connection = connections.get(session.getId());
    if (connection != null) {
      synchronized (connection) {
        connection.lastPong = clock.instant();
      }
    }
  }

  private void send(Connection connection, Scope scope, String resource, long revision,
      UUID entityId) throws IOException {
    sendMessage(connection, new Changed("changed", scope.organizationId(), scope.accountId(),
        resource, revision, entityId));
  }

  private void sendMessage(Connection connection, Object payload) throws IOException {
    TextMessage message = new TextMessage(json.encode(payload));
    int bytes = message.getPayload().getBytes(StandardCharsets.UTF_8).length;
    long pending = pendingBytes.addAndGet(bytes);
    try {
      if (bytes > 8192 || pending > MAX_PENDING_BYTES) {
        close(connection.session, CloseStatus.SERVICE_RESTARTED);
        return;
      }
      // Sends are serialized per connection, so there is at most one pending notification.
      connection.session.sendMessage(message);
    } finally {
      pendingBytes.addAndGet(-bytes);
    }
  }

  private static void removeUnusedScopes(Connection connection) {
    Set<Scope> used = new HashSet<>();
    connection.subscriptions.values().forEach(subscription -> used.add(subscription.scope()));
    connection.scopes.keySet().retainAll(used);
  }

  private void close(WebSocketSession session, CloseStatus status) throws IOException {
    connections.remove(session.getId());
    session.close(status);
  }

  private void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      close(session, status);
    } catch (IOException exception) {
      log.debug("Realtime transport already closed");
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
    connections.remove(session.getId());
  }

  private record Subscribe(String type, String subscriptionId, UUID organizationId,
      UUID accountId, Set<String> resources) {}

  private record Acknowledged(String type, String subscriptionId) {}

  private record Changed(String type, UUID organizationId, UUID accountId, String resource,
      long revision, UUID entityId) {}

  private record Subscription(Scope scope, Set<String> resources, int metadataBytes) {}

  private static final class ScopeCursor {
    private final Set<String> permissions;
    private long cursor;

    private ScopeCursor(long cursor, Set<String> permissions) {
      this.cursor = cursor;
      this.permissions = permissions;
    }
  }

  private static final class Connection {
    private final WebSocketSession session;
    private final UUID subject;
    private final Instant expiresAt;
    private final Map<String, Subscription> subscriptions = new HashMap<>();
    private final Map<Scope, ScopeCursor> scopes = new HashMap<>();
    private long controlSecond;
    private int controlCount;
    private Instant lastPing;
    private Instant lastPong;

    private Connection(WebSocketSession session, UUID subject, Instant expiresAt, Instant now) {
      this.session = session;
      this.subject = subject;
      this.expiresAt = expiresAt;
      this.lastPing = now;
      this.lastPong = now;
    }
  }
}
