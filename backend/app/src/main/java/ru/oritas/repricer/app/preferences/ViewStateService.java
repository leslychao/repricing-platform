package ru.oritas.repricer.app.preferences;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.marketplace.MarketplaceReadService;

@Service
public final class ViewStateService {
  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final AuthorizationService authorization;
  private final IdempotencyService requests;
  private final MarketplaceReadService marketplace;
  private final Clock clock;

  public ViewStateService(JdbcClient jdbc, JsonCodec json, AuthorizationService authorization,
      IdempotencyService requests, MarketplaceReadService marketplace, Clock clock) {
    this.jdbc = jdbc;
    this.json = json;
    this.authorization = authorization;
    this.requests = requests;
    this.marketplace = marketplace;
    this.clock = clock;
  }

  public SavedView get(Scope scope, String key) {
    authorize(scope, key, "view.read");
    return find(scope, key);
  }

  public SavedView save(Scope scope, String key, SaveRequest request) {
    authorize(scope, key, "view.write");
    validate(request.state());
    return requests.execute(scope, "view.save:" + key, request.clientRequestId(), request,
        SavedView.class, () -> change(scope, key, request, false));
  }

  public SavedView reset(Scope scope, String key, RevisionRequest request) {
    authorize(scope, key, "view.write");
    return requests.execute(scope, "view.reset:" + key, request.clientRequestId(), request,
        SavedView.class, () -> {
          SavedView current = find(scope, key);
          return change(scope, key, new SaveRequest(request.clientRequestId(),
              request.expectedRevision(), current.resetGeneration(), defaults(scope, key)), true);
        });
  }

  private SavedView change(Scope scope, String key, SaveRequest request, boolean reset) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:lock,0))")
        .param("lock", "view:" + scope + ":" + key).query((row, index) -> true).single();
    var current = jdbc.sql("""
        SELECT revision,reset_generation FROM app_view_state WHERE view_key=:key FOR UPDATE
        """).param("key", key).query((row, index) -> new long[] {row.getLong(1), row.getLong(2)})
        .optional();
    long revision = current.map(value -> value[0]).orElse(0L);
    long generation = current.map(value -> value[1]).orElse(0L);
    if (request.expectedRevision() != revision || request.resetGeneration() != generation) {
      throw new BusinessException("VIEW_CONFLICT", 409,
          "Вид изменён в другом окне. Перечитайте его перед сохранением");
    }
    long nextGeneration = generation + (reset ? 1 : 0);
    jdbc.sql("""
        INSERT INTO app_view_state(organization_id,account_id,subject_id,view_key,
          revision,reset_generation,state) VALUES (:org,:account,:subject,:key,:revision,
          :generation,CAST(:state AS jsonb))
        ON CONFLICT(subject_id,scope_key,view_key) DO UPDATE SET revision=EXCLUDED.revision,
          reset_generation=EXCLUDED.reset_generation,state=EXCLUDED.state,
          updated_at=clock_timestamp()
        """).param("org", scope.organizationId()).param("account", scope.accountId())
        .param("subject", scope.subjectId()).param("key", key).param("revision", revision + 1)
        .param("generation", nextGeneration).param("state", json.encode(request.state())).update();
    return new SavedView(1, revision + 1, nextGeneration, request.state());
  }

  private SavedView find(Scope scope, String key) {
    return jdbc.sql("SELECT revision,reset_generation,state::text FROM app_view_state WHERE view_key=:key")
        .param("key", key).query((row, index) -> new SavedView(1, row.getLong(1), row.getLong(2),
            json.decode(row.getString(3), State.class))).optional()
        .orElseGet(() -> new SavedView(1, 0, 0, defaults(scope, key)));
  }

  private void authorize(Scope scope, String key, String permission) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (!key.matches("[a-zA-Z0-9_.:/-]{1,160}")) {
      throw new BusinessException("INVALID_VIEW", 422, "Некорректный ключ вида");
    }
    if (scope.organizationId() != null) {
      authorization.require(scope, permission);
    }
  }

  private void validate(State state) {
    if (state == null || state.query() == null || state.columns() == null
        || state.presentation() == null || state.navigation() == null) {
      throw new BusinessException("INVALID_VIEW", 422, "Неполные настройки вида");
    }
    Page.offset(state.query().page(), state.query().size());
    if (state.query().search() == null || state.query().sort() == null
        || state.query().filters() == null || state.columns().order() == null
        || state.columns().hidden() == null || state.columns().widths() == null
        || state.presentation().expanded() == null
        || state.query().search().length() > 512 || state.query().sort().length() > 512
        || state.query().filters().size() > 20 || state.query().sort().split(";", -1).length > 5
        || state.columns().order().size() > 50 || state.columns().hidden().size() > 50
        || state.columns().widths().size() > 50 || state.presentation().expanded().size() > 200
        || state.columns().widths().values().stream().anyMatch(width -> width == null || width < 80 || width > 800)
        || state.query().filters().entrySet().stream().anyMatch(entry -> invalidText(entry.getKey()) || invalidText(entry.getValue()))
        || state.columns().order().stream().anyMatch(ViewStateService::invalidText)
        || state.columns().hidden().stream().anyMatch(ViewStateService::invalidText)
        || state.columns().widths().keySet().stream().anyMatch(ViewStateService::invalidText)
        || state.presentation().expanded().stream().anyMatch(ViewStateService::invalidText)) {
      throw new BusinessException("INVALID_VIEW", 422, "Превышены ограничения личного вида");
    }
    json.encode(state);
  }

  private static boolean invalidText(String value) {
    return value == null || value.length() > 512;
  }

  private State defaults(Scope scope, String key) {
    if (key.equals("analytics")) {
      var zone = scope.accountId() == null ? ZoneOffset.UTC : marketplace.zoneId(scope);
      String month = YearMonth.now(clock.withZone(zone)).toString();
      return new State(new Query("", Map.of("month", month), "id,asc", 0, 50),
          new Columns(List.of(), List.of(), Map.of()),
          new Presentation(List.of()), new Navigation(false));
    }
    if (!Set.of("finance", "payments", "returns", "audit", "account-audit", "tasks", "imports", "reports").contains(key)) {
      return State.defaults();
    }
    var zone = scope.accountId() == null ? ZoneOffset.UTC : marketplace.zoneId(scope);
    LocalDate today = LocalDate.now(clock.withZone(zone));
    return new State(new Query("", Map.of("from", today.minusDays(29).toString(), "to", today.toString()),
        "date,desc", 0, 50), new Columns(List.of(), List.of(), Map.of()),
        new Presentation(List.of()), new Navigation(false));
  }

  public record Query(String search, Map<String, String> filters, String sort, int page, int size) {}

  public record Columns(List<String> order, List<String> hidden, Map<String, Integer> widths) {}

  public record Presentation(List<String> expanded) {}

  public record Navigation(boolean collapsed) {}

  public record State(Query query, Columns columns, Presentation presentation, Navigation navigation) {
    public static State defaults() {
      return new State(new Query("", Map.of(), "id,asc", 0, 50),
          new Columns(List.of(), List.of(), Map.of()), new Presentation(List.of()),
          new Navigation(false));
    }
  }

  public record SavedView(int schemaVersion, long revision, long resetGeneration, State state) {}

  public record SaveRequest(UUID clientRequestId, long expectedRevision, long resetGeneration,
      State state) {}

  public record RevisionRequest(UUID clientRequestId, long expectedRevision) {}
}
