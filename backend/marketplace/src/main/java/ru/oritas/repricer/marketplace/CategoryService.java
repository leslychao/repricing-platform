package ru.oritas.repricer.marketplace;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** Publishes one complete official tree and binds only source-confirmed product categories. */
@Service
public final class CategoryService {
  private final JdbcClient jdbc;
  private final NamedParameterJdbcTemplate batch;
  private final AuthorizationService authorization;
  private final OutboxService outbox;

  public CategoryService(
      JdbcClient jdbc,
      NamedParameterJdbcTemplate batch,
      AuthorizationService authorization,
      OutboxService outbox) {
    this.jdbc = jdbc;
    this.batch = batch;
    this.authorization = authorization;
    this.outbox = outbox;
  }

  void stage(Scope scope, UUID runId, List<CategoryTreeReader.Node> nodes) {
    authorization.require(scope, "sync.request");
    if (nodes.size() > 100) {
      throw new IllegalArgumentException("A category batch contains at most 100 nodes");
    }
    var parameters =
        nodes.stream()
            .map(
                node ->
                    new MapSqlParameterSource()
                        .addValue("org", scope.organizationId())
                        .addValue("account", scope.requireAccount())
                        .addValue("run", runId)
                        .addValue("sequence", node.sequence())
                        .addValue("parent", node.parentSequence())
                        .addValue("external", node.externalId())
                        .addValue("kind", node.kind())
                        .addValue("name", node.name())
                        .addValue("disabled", node.disabled()))
            .toArray(MapSqlParameterSource[]::new);
    batch.batchUpdate(
        """
        INSERT INTO marketplace_category_stage(organization_id,account_id,run_id,sequence,
          parent_sequence,external_id,kind,name,disabled)
        VALUES (:org,:account,:run,:sequence,:parent,:external,:kind,:name,:disabled)
        ON CONFLICT DO NOTHING
        """,
        parameters);
    var stored =
        jdbc.sql(
                """
                SELECT sequence,parent_sequence,external_id,kind,name,disabled FROM marketplace_category_stage
                WHERE organization_id=:org AND account_id=:account AND run_id=:run AND sequence IN (:sequences)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("run", runId)
            .param("sequences", nodes.stream().map(CategoryTreeReader.Node::sequence).toList())
            .query(
                (row, index) ->
                    new CategoryTreeReader.Node(
                        row.getInt(1),
                        row.getObject(2, Integer.class),
                        row.getString(3),
                        row.getString(4),
                        row.getString(5),
                        row.getBoolean(6)))
            .list();
    if (!java.util.Set.copyOf(nodes).equals(java.util.Set.copyOf(stored))) {
      throw new BusinessException(
          "CATEGORY_PARSE_CONFLICT", 409, "Повторный разбор исходника изменил категорию");
    }
  }

  private static final String TREE =
      """
      WITH RECURSIVE nodes AS (
        SELECT s.*,CASE WHEN s.kind='TYPE' THEN p.external_id||':'||s.external_id
          ELSE s.external_id END AS source_key,
          md5(s.account_id::text||':category:'||s.kind||':'||CASE WHEN s.kind='TYPE'
            THEN p.external_id||':'||s.external_id ELSE s.external_id END)::uuid AS id
        FROM marketplace_category_stage s LEFT JOIN marketplace_category_stage p
          ON (p.organization_id,p.account_id,p.run_id,p.sequence)=
            (s.organization_id,s.account_id,s.run_id,s.parent_sequence) AND p.kind='CATEGORY'
        WHERE s.organization_id=:org AND s.account_id=:account AND s.run_id=:run
      ), tree AS (
        SELECT n.*,NULL::uuid AS parent_id,ARRAY[]::uuid[] AS ancestors FROM nodes n
        WHERE parent_sequence IS NULL AND kind='CATEGORY'
        UNION ALL
        SELECT n.*,p.id,p.id||p.ancestors FROM nodes n JOIN tree p
          ON n.parent_sequence=p.sequence WHERE cardinality(p.ancestors)<63
      )
      """;

  void publish(Scope scope, UUID runId, UUID rawId, Instant observedAt, int expectedCount) {
    authorization.require(scope, "sync.request");
    lockAccount(scope);
    Map<String, Object> parameters =
        Map.of(
            "org",
            scope.organizationId(),
            "account",
            scope.requireAccount(),
            "run",
            runId,
            "raw",
            rawId,
            "observed",
            Timestamp.from(observedAt));
    record Validation(long total, long uniqueIds, boolean valid) {}
    var validation =
        jdbc.sql(
                TREE
                    + "SELECT count(*),count(DISTINCT id),COALESCE(bool_and(id IS NOT NULL),false)"
                    + " FROM tree")
            .params(parameters)
            .query(
                (row, index) -> new Validation(row.getLong(1), row.getLong(2), row.getBoolean(3)))
            .single();
    if (!validation.valid()
        || validation.total() != expectedCount
        || validation.uniqueIds() != expectedCount) {
      throw new BusinessException(
          "CATEGORY_TREE_INCOMPLETE", 422, "Дерево категорий неоднозначно или неполно");
    }
    if (jdbc.sql("SELECT EXISTS(SELECT 1 FROM marketplace_category WHERE observed_at>:observed)")
        .param("observed", Timestamp.from(observedAt))
        .query(Boolean.class)
        .single()) {
      throw new BusinessException(
          "SOURCE_OBSERVATION_SUPERSEDED", 409, "Получено более новое дерево категорий");
    }
    jdbc.sql(
            TREE
                + """
                INSERT INTO marketplace_category(organization_id,account_id,id,external_id,kind,name,parent_id,
                  ancestors,disabled,current,revision,publication_id,raw_file_id,observed_at,valid_until)
                SELECT organization_id,account_id,id,source_key,kind,name,parent_id,ancestors,disabled,true,1,
                  :run,:raw,:observed,CAST(:observed AS timestamptz)+interval '1 day' FROM tree
                ON CONFLICT(organization_id,account_id,id) DO UPDATE SET name=EXCLUDED.name,
                  parent_id=EXCLUDED.parent_id,ancestors=EXCLUDED.ancestors,disabled=EXCLUDED.disabled,current=true,
                  publication_id=EXCLUDED.publication_id,raw_file_id=EXCLUDED.raw_file_id,
                  observed_at=EXCLUDED.observed_at,valid_until=EXCLUDED.valid_until,
                  revision=marketplace_category.revision+CASE WHEN
                    (marketplace_category.name,marketplace_category.parent_id,marketplace_category.ancestors,
                      marketplace_category.disabled,marketplace_category.current)
                    IS DISTINCT FROM (EXCLUDED.name,EXCLUDED.parent_id,EXCLUDED.ancestors,EXCLUDED.disabled,true)
                    THEN 1 ELSE 0 END
                """)
        .params(parameters)
        .update();
    jdbc.sql(
            """
            UPDATE marketplace_category SET current=false,revision=revision+1
            WHERE organization_id=:org AND account_id=:account AND current AND publication_id<>:run
            """)
        .params(parameters)
        .update();
    refreshOfferPaths(scope);
  }

  /** Reused by catalog publication so a changed product binding never keeps its former category. */
  void refreshOfferPaths(Scope scope) {
    authorization.require(scope, "sync.request");
    lockAccount(scope);
    int updated =
        jdbc.sql(
                """
                WITH bindings AS (
                  SELECT o.id,CASE WHEN c.id IS NULL THEN NULL ELSE ARRAY[c.id]||c.ancestors END AS path FROM marketplace_offer o
                  JOIN marketplace_account a ON (a.organization_id,a.id)=(o.organization_id,o.account_id)
                  LEFT JOIN marketplace_category c ON (c.organization_id,c.account_id)=(o.organization_id,o.account_id)
                    AND c.current AND c.valid_until>clock_timestamp()
                    AND ((a.marketplace='YANDEX' AND c.kind='CATEGORY'
                        AND c.external_id=o.catalog_fields#>>'{mapping,marketCategoryId}')
                      OR (a.marketplace='OZON' AND c.kind='TYPE'
                        AND c.external_id=(o.catalog_fields->>'description_category_id')||':'||(o.catalog_fields->>'type_id')))
                  WHERE o.organization_id=:org AND o.account_id=:account
                ) UPDATE marketplace_offer o SET category_path=b.path,revision=revision+1 FROM bindings b
                  WHERE o.organization_id=:org AND o.account_id=:account AND o.id=b.id
                    AND o.category_path IS DISTINCT FROM b.path
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .update();
    if (updated > 0) {
      outbox.emit(
          scope,
          "categories:offers:" + UUID.randomUUID(),
          "catalog.categories.changed",
          new OutboxService.EntityChange("offers", scope.accountId(), 0));
    }
  }

  private void lockAccount(Scope scope) {
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
  }
}
