package ru.oritas.repricer.automation.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.SqlReadProjection;
import ru.oritas.repricer.platform.TableExportSource;

/** One company policy publication; assignments select it without copying its commercial rules. */
@Service
public class PolicyService implements TableExportSource {
  private static final String ASSIGNMENT_SUMMARY_SQL =
      """
      LEFT JOIN LATERAL (
        SELECT CASE WHEN :assignmentAccount='' THEN NULL ELSE count(*) END AS assignments_count,
          CASE WHEN :assignmentAccount='' THEN NULL ELSE
            COALESCE(jsonb_agg(DISTINCT a.scope_kind ORDER BY a.scope_kind),'[]'::jsonb)::text
          END AS assignment_scopes
        FROM automation_assignment a
        WHERE a.organization_id=p.organization_id AND a.policy_id=p.id
          AND a.account_id=CAST(NULLIF(:assignmentAccount,'') AS uuid) AND NOT a.detached
      ) assignment_summary ON true
      """;

  public record PolicyInput(
      String name, String description, PolicySettings settings, long expectedRevision) {
    public PolicyInput {
      if (name == null
          || name.isBlank()
          || name.length() > 120
          || description == null
          || description.length() > 2000
          || settings == null
          || expectedRevision < 0) {
        throw new IllegalArgumentException("Invalid policy draft");
      }
    }
  }

  public record Policy(
      UUID id,
      String name,
      String description,
      String status,
      long version,
      long revision,
      String strategy,
      Long assignmentsCount,
      List<String> assignmentScopes,
      PolicySettings settings,
      PolicySettings draft) {}

  public record AssignmentInput(
      UUID policyId,
      String scope,
      UUID targetId,
      String mode,
      boolean enabled,
      long expectedRevision,
      LocalReferences regularReferences,
      LocalReferences temporaryReferences) {
    public AssignmentInput(
        UUID policyId,
        String scope,
        UUID targetId,
        String mode,
        boolean enabled,
        long expectedRevision) {
      this(policyId, scope, targetId, mode, enabled, expectedRevision, null, null);
    }

    public AssignmentInput {
      regularReferences = regularReferences == null ? LocalReferences.empty() : regularReferences;
      temporaryReferences =
          temporaryReferences == null ? LocalReferences.empty() : temporaryReferences;
      if (policyId == null
          || !Set.of("ACCOUNT", "CATEGORY", "OFFER").contains(scope)
          || (!scope.equals("ACCOUNT") && targetId == null)
          || !Set.of("PREVIEW", "MANUAL", "AUTO").contains(mode)
          || expectedRevision < 0) {
        throw new IllegalArgumentException("Invalid policy assignment");
      }
    }
  }

  public record Assignment(
      UUID id,
      UUID policyId,
      String scope,
      UUID targetId,
      String mode,
      boolean enabled,
      boolean paused,
      long revision,
      LocalReferences regularReferences,
      LocalReferences temporaryReferences,
      boolean detached) {}

  public record LocalReferences(
      Set<UUID> selectedPromos,
      Set<UUID> protectedPromos,
      UUID competitorSource,
      List<Set<UUID>> allowedCombinations,
      Set<PolicySettings.DiscoveryProfile> discoveryProfiles,
      Set<UUID> excludedPromos) {
    public LocalReferences(
        Set<UUID> selectedPromos, Set<UUID> protectedPromos, UUID competitorSource) {
      this(selectedPromos, protectedPromos, competitorSource, List.of(), Set.of(), Set.of());
    }

    public LocalReferences {
      selectedPromos = selectedPromos == null ? Set.of() : Set.copyOf(selectedPromos);
      protectedPromos = protectedPromos == null ? Set.of() : Set.copyOf(protectedPromos);
      allowedCombinations =
          allowedCombinations == null
              ? List.of()
              : allowedCombinations.stream().map(Set::copyOf).toList();
      discoveryProfiles = discoveryProfiles == null ? Set.of() : Set.copyOf(discoveryProfiles);
      excludedPromos = excludedPromos == null ? Set.of() : Set.copyOf(excludedPromos);
      PolicySettings.validatePromotionReferences(
          selectedPromos, protectedPromos, allowedCombinations, discoveryProfiles, excludedPromos);
    }

    public static LocalReferences empty() {
      return new LocalReferences(Set.of(), Set.of(), null);
    }
  }

  public record Publication(
      long version, PolicySettings settings, UUID authorId, java.time.Instant publishedAt) {}

  public record Changed(String resource, UUID entityId, long revision) {}

  public record ActivePolicy(
      UUID policyId,
      long version,
      UUID assignmentId,
      long assignmentRevision,
      String mode,
      boolean active,
      PolicySettings settings) {}

  public record CapturedOffer(
      UUID offerId,
      long offerRevision,
      String categoryPath,
      UUID effectiveAssignmentId,
      long effectiveAssignmentRevision,
      UUID effectivePolicyId,
      long effectivePolicyVersion,
      long directRevision,
      LocalReferences regularReferences,
      LocalReferences temporaryReferences) {}

  /** Captures configuration heads together; callers materialize the full set before effects. */
  public List<CapturedOffer> captureOffers(Scope scope, List<UUID> offerIds) {
    authorization.require(scope, "policy.read");
    if (offerIds.size() > 200) {
      throw new IllegalArgumentException("At most 200 offers per capture");
    }
    if (offerIds.isEmpty()) {
      return List.of();
    }
    var categories = marketplace.categoryPathsProjection(scope);
    return jdbc.sql(
            """
            SELECT o.id,o.revision,o.category_path::text,selected.id AS assignment_id,
              COALESCE(selected.revision,0) AS assignment_revision,selected.policy_id,
              COALESCE(p.version,0) AS policy_version,COALESCE(direct.revision,0) AS direct_revision,
              direct.regular_references::text,direct.temporary_references::text
            FROM (%s) o LEFT JOIN LATERAL (
              SELECT a.* FROM automation_assignment a WHERE a.organization_id=o.organization_id
                AND a.account_id=o.account_id AND NOT a.detached AND ((a.scope_kind='ACCOUNT' AND a.target_id=o.account_id AND o.category_path IS NOT NULL)
                  OR (a.scope_kind='OFFER' AND a.target_id=o.id)
                  OR (a.scope_kind='CATEGORY' AND a.target_id=ANY(o.category_path)))
              ORDER BY CASE a.scope_kind WHEN 'OFFER' THEN 0 WHEN 'CATEGORY' THEN 1 ELSE 2 END,
                array_position(o.category_path,a.target_id) NULLS LAST LIMIT 1
            ) selected ON true LEFT JOIN automation_policy p
              ON p.organization_id=o.organization_id AND p.id=selected.policy_id
            LEFT JOIN automation_assignment direct ON direct.organization_id=o.organization_id
              AND direct.account_id=o.account_id AND NOT direct.detached AND direct.scope_kind='OFFER' AND direct.target_id=o.id
            WHERE o.organization_id=:org AND o.account_id=:account AND o.id IN (:offers)
            ORDER BY o.id
            """
                .formatted(categories.sql()))
        .params(categories.parameters())
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("offers", offerIds)
        .query(
            (row, index) ->
                new CapturedOffer(
                    row.getObject("id", UUID.class),
                    row.getLong("revision"),
                    row.getString("category_path"),
                    row.getObject("assignment_id", UUID.class),
                    row.getLong("assignment_revision"),
                    row.getObject("policy_id", UUID.class),
                    row.getLong("policy_version"),
                    row.getLong("direct_revision"),
                    row.getString("regular_references") == null
                        ? LocalReferences.empty()
                        : json.decode(row.getString("regular_references"), LocalReferences.class),
                    row.getString("temporary_references") == null
                        ? LocalReferences.empty()
                        : json.decode(
                            row.getString("temporary_references"), LocalReferences.class)))
        .list();
  }

  public List<UUID> assignmentOffers(Scope scope, UUID assignmentId, UUID after, int limit) {
    authorization.require(scope, "policy.read");
    if (limit < 1 || limit > 200) {
      throw new IllegalArgumentException("Invalid assignment capture limit");
    }
    var categories = marketplace.categoryPathsProjection(scope);
    return jdbc.sql(
            """
            SELECT o.id FROM (%s) o CROSS JOIN LATERAL (
              SELECT a.id FROM automation_assignment a WHERE a.organization_id=o.organization_id
                AND a.account_id=o.account_id AND NOT a.detached AND ((a.scope_kind='ACCOUNT' AND a.target_id=o.account_id)
                  OR (a.scope_kind='OFFER' AND a.target_id=o.id)
                  OR (a.scope_kind='CATEGORY' AND a.target_id=ANY(o.category_path)))
              ORDER BY CASE a.scope_kind WHEN 'OFFER' THEN 0 WHEN 'CATEGORY' THEN 1 ELSE 2 END,
                array_position(o.category_path,a.target_id) NULLS LAST LIMIT 1
            ) selected WHERE o.organization_id=:org AND o.account_id=:account AND selected.id=:assignment
              AND (CAST(:after AS uuid) IS NULL OR o.id>:after) ORDER BY o.id LIMIT :limit
            """
                .formatted(categories.sql()))
        .params(categories.parameters())
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("assignment", assignmentId)
        .param("after", after)
        .param("limit", limit)
        .query(UUID.class)
        .list();
  }

  public long requireBatchPolicy(Scope scope, UUID policyId) {
    authorization.requireLocked(scope, Set.of("policy.assign", "finance.read"));
    Policy policy = read(scope, policyId, true);
    if (policy.version() == 0 || policy.status().equals("ARCHIVED")) {
      throw new BusinessException("POLICY_UNPUBLISHED", 409, "Опубликуйте политику");
    }
    return policy.version();
  }

  public long assignmentPolicyVersion(Scope scope, UUID assignmentId) {
    Assignment assignment = getAssignment(scope, assignmentId);
    return read(scope, assignment.policyId(), false).version();
  }

  public void requireCaptured(Scope scope, CapturedOffer captured) {
    lockAssignments(scope);
    if (captured.effectivePolicyId() != null) {
      read(scope, captured.effectivePolicyId(), true);
    }
    List<CapturedOffer> current = captureOffers(scope, List.of(captured.offerId()));
    if (current.isEmpty() || !current.getFirst().equals(captured)) {
      throw new BusinessException(
          "ASSIGNMENT_BASIS_CHANGED",
          409,
          "Товар или его назначение изменились после фиксации выборки");
    }
  }

  public Assignment applyCaptured(
      Scope scope, CapturedOffer captured, UUID policyId, long policyVersion, String mode) {
    lockAssignments(scope);
    if (requireBatchPolicy(scope, policyId) != policyVersion) {
      throw new BusinessException("POLICY_PUBLICATION_CHANGED", 409, "Публикация изменилась");
    }
    requireCaptured(scope, captured);
    return assign(
        scope,
        new AssignmentInput(
            policyId,
            "OFFER",
            captured.offerId(),
            mode,
            true,
            captured.directRevision(),
            captured.regularReferences(),
            captured.temporaryReferences()));
  }

  private void lockAssignments(Scope scope) {
    // Admission holds the same account row: a more specific new binding cannot be a phantom.
    jdbc.sql("SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope,0))")
        .param(
            "scope", "policy-assignments:" + scope.organizationId() + ":" + scope.requireAccount())
        .query(Object.class)
        .single();
  }

  private final JdbcClient jdbc;
  private final JsonCodec json;
  private final AuthorizationService authorization;
  private final AuditService audit;
  private final OutboxService outbox;
  private final MarketplaceReadService marketplace;

  public PolicyService(
      JdbcClient jdbc,
      JsonCodec json,
      AuthorizationService authorization,
      AuditService audit,
      OutboxService outbox,
      MarketplaceReadService marketplace) {
    this.jdbc = jdbc;
    this.json = json;
    this.authorization = authorization;
    this.audit = audit;
    this.outbox = outbox;
    this.marketplace = marketplace;
  }

  public List<Policy> list(Scope scope, int limit, int offset) {
    authorization.require(scope, "organization.policy.read");
    if (limit < 1 || limit > 200 || offset < 0) {
      throw new IllegalArgumentException("Invalid page");
    }
    return jdbc.sql(
            """
            SELECT p.*,v.settings::text AS published_settings,
              assignment_summary.assignments_count,assignment_summary.assignment_scopes
            FROM automation_policy p LEFT JOIN automation_policy_publication v
              ON v.organization_id=p.organization_id AND v.policy_id=p.id AND v.version=p.version
            """
                + ASSIGNMENT_SUMMARY_SQL
                + """
                WHERE p.organization_id=:org ORDER BY p.created_at,p.id LIMIT :limit OFFSET :offset
                """)
        .param("org", scope.requireOrganization())
        .param("assignmentAccount", assignmentAccount(scope))
        .param("limit", limit)
        .param("offset", offset)
        .query(this::mapPolicy)
        .list();
  }

  public Page<Policy> page(Scope scope, Query query) {
    authorization.require(scope, "organization.policy.read");
    int page = query.page();
    int size = query.size();
    int offset = Page.offset(page, size);
    var selection = policySelection(scope, query, List.of());
    long total =
        jdbc.sql("SELECT count(*) " + selection.source())
            .params(selection.parameters())
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT p.* "
                    + selection.source()
                    + " ORDER BY "
                    + selection.ordering()
                    + ",p.id LIMIT :size OFFSET :offset")
            .params(selection.parameters())
            .param("size", size)
            .param("offset", offset)
            .query(this::mapPolicy)
            .list();
    return new Page<>(items, total, page, size);
  }

  @Override
  public String resource() {
    return "policies";
  }

  @Override
  public Set<String> permissions() {
    return Set.of("organization.policy.read");
  }

  @Override
  public Projection projection(Scope scope, Query query, List<UUID> ids) {
    authorization.require(scope, "organization.policy.read");
    var selection = policySelection(scope, query, ids);
    return new Projection(
        """
        SELECT p.id AS canonical_id,jsonb_build_array(p.name,p.status,p.version::text,
          p.assignments_count::text,p.assignment_scopes,p.selected_strategy) AS cells,
          CASE WHEN :assignmentAccount='' THEN '[]'::jsonb
            ELSE '["policy.read"]'::jsonb END AS permissions
        """
            + selection.source()
            + " ORDER BY "
            + selection.ordering()
            + ",p.id",
        selection.parameters(),
        List.of(
            new Column("name", "Политика", false),
            new Column("status", "Состояние", false),
            new Column("version", "Версия", true),
            new Column("assignments", "Назначения", true),
            new Column("scopes", "Области", false),
            new Column("strategy", "Стратегия", false)),
        "AS_PUBLISHED",
        true);
  }

  private record PolicySelection(String source, String ordering, Map<String, Object> parameters) {}

  private PolicySelection policySelection(Scope scope, Query query, List<UUID> ids) {
    String status = query.filters().getOrDefault("status", "");
    String assignmentScope = query.filters().getOrDefault("scopes", "");
    String hasAssignments = query.filters().getOrDefault("hasAssignments", "");
    String strategy = query.filters().getOrDefault("strategy", "");
    String version = query.filters().getOrDefault("version", "");
    if (query.search().length() > 200
        || ids.size() > 1000
        || !Set.of("status", "scopes", "hasAssignments", "strategy", "version")
            .containsAll(query.filters().keySet())
        || !Set.of("", "ACCOUNT", "CATEGORY", "OFFER").contains(assignmentScope)
        || !Set.of("", "YES", "NO").contains(hasAssignments)
        || !Set.of("", "HOLD_PRICE", "TARGET_PROFITABILITY", "FOLLOW_COMPETITOR").contains(strategy)
        || (!version.isEmpty() && !version.matches("[0-9]{1,9}"))
        || !Set.of("", "DRAFT", "ACTIVE", "PAUSED", "ARCHIVED").contains(status)) {
      throw new IllegalArgumentException("Invalid policy filter");
    }
    String[] sort = query.sort().split(",", -1);
    if (sort.length > 2) {
      throw new IllegalArgumentException("Invalid policy sort");
    }
    String column =
        switch (sort[0]) {
          case "", "name" -> "p.name";
          case "status" -> "p.status";
          case "version" -> "p.version";
          case "assignments" -> "p.assignments_count";
          case "scopes" -> "p.assignment_scopes";
          case "strategy" -> "p.selected_strategy";
          case "createdAt" -> "p.created_at";
          case "id" -> "p.id";
          default -> throw new IllegalArgumentException("Invalid policy sort");
        };
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("org", scope.requireOrganization());
    parameters.put("search", query.search());
    parameters.put("status", status);
    String account = assignmentAccount(scope);
    if (account.isEmpty() && (!assignmentScope.isEmpty() || !hasAssignments.isEmpty())) {
      throw new BusinessException(
          "ASSIGNMENT_SCOPE_UNAVAILABLE", 403, "Для фильтра назначений выберите доступный кабинет");
    }
    parameters.put("assignmentAccount", account);
    parameters.put("assignmentScope", assignmentScope);
    parameters.put("hasAssignments", hasAssignments);
    parameters.put("strategy", strategy);
    parameters.put("version", version);
    String source =
        """
        FROM (SELECT p.*,publication.settings::text AS published_settings,
          COALESCE(publication.settings,p.draft)#>>'{regularRule,strategy}' AS selected_strategy,
          assignment_summary.assignments_count,assignment_summary.assignment_scopes
          FROM automation_policy p LEFT JOIN automation_policy_publication publication
            ON publication.organization_id=p.organization_id AND publication.policy_id=p.id
              AND publication.version=p.version
        """
            + ASSIGNMENT_SUMMARY_SQL
            + """
              WHERE p.organization_id=:org) p
            WHERE position(lower(:search) in lower(p.name))>0 AND (:status='' OR p.status=:status)
              AND (:assignmentScope='' OR jsonb_exists(p.assignment_scopes::jsonb,:assignmentScope))
              AND (:hasAssignments='' OR (:hasAssignments='YES' AND p.assignments_count>0)
                OR (:hasAssignments='NO' AND p.assignments_count=0))
              AND (:strategy='' OR p.selected_strategy=:strategy)
              AND (:version='' OR p.version=CAST(NULLIF(:version,'') AS bigint))
            """;
    if (!ids.isEmpty()) {
      parameters.put("selected", ids);
      source += " AND p.id IN (:selected)";
    }
    return new PolicySelection(
        source, column + ordering(sort.length == 2 ? sort[1] : "asc") + " NULLS LAST", parameters);
  }

  public Page<Publication> publications(Scope scope, UUID policyId, int page, int size) {
    authorization.require(scope, "organization.policy.read");
    read(scope, policyId, false);
    long offset = Page.offset(page, size);
    long total =
        jdbc.sql(
                """
                SELECT count(*) FROM automation_policy_publication
                WHERE organization_id=:org AND policy_id=:id
                """)
            .param("org", scope.organizationId())
            .param("id", policyId)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                """
                SELECT * FROM automation_policy_publication WHERE organization_id=:org AND policy_id=:id
                ORDER BY version DESC LIMIT :size OFFSET :offset
                """)
            .param("org", scope.organizationId())
            .param("id", policyId)
            .param("size", size)
            .param("offset", offset)
            .query(
                (rs, row) ->
                    new Publication(
                        rs.getLong("version"),
                            json.decode(rs.getString("settings"), PolicySettings.class),
                        rs.getObject("author_id", UUID.class),
                            rs.getTimestamp("created_at").toInstant()))
            .list();
    return new Page<>(items, total, page, size);
  }

  public Policy get(Scope scope, UUID id) {
    authorization.require(scope, "organization.policy.read");
    return read(scope, id, false);
  }

  @Transactional
  public Policy create(Scope scope, PolicyInput input) {
    authorization.require(scope, "organization.policy.manage");
    companyRules(input.settings());
    if (input.expectedRevision() != 0) {
      throw conflict();
    }
    UUID id = UUID.randomUUID();
    jdbc.sql(
            """
            INSERT INTO automation_policy(organization_id,id,name,description,status,version,
              revision,draft,author_id)
            VALUES (:org,:id,:name,:description,'DRAFT',0,1,CAST(:settings AS jsonb),:author)
            """)
        .param("org", scope.requireOrganization())
        .param("id", id)
        .param("name", input.name())
        .param("description", input.description())
        .param("settings", json.encode(input.settings()))
        .param("author", scope.subjectId())
        .update();
    changed(scope, id, 1, "POLICY_CREATED");
    return read(scope, id, false);
  }

  @Transactional
  public Policy saveDraft(Scope scope, UUID id, PolicyInput input) {
    authorization.require(scope, "organization.policy.manage");
    companyRules(input.settings());
    Policy policy = read(scope, id, true);
    if (policy.revision() != input.expectedRevision() || policy.status().equals("ARCHIVED")) {
      throw conflict();
    }
    if (policy.name().equals(input.name())
        && policy.description().equals(input.description())
        && input.settings().equals(policy.draft())) {
      return policy;
    }
    jdbc.sql(
            """
            UPDATE automation_policy SET name=:name,description=:description,
              draft=CAST(:draft AS jsonb),revision=revision+1
            WHERE organization_id=:org AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("id", id)
        .param("name", input.name())
        .param("description", input.description())
        .param("draft", json.encode(input.settings()))
        .update();
    changed(scope, id, policy.revision() + 1, "POLICY_DRAFT_SAVED");
    return read(scope, id, false);
  }

  @Transactional(timeout = 5)
  public Policy publish(Scope scope, UUID id, long expectedRevision) {
    requirePublicationPermissions(scope, id);
    Policy policy = read(scope, id, true);
    if (policy.revision() != expectedRevision || policy.status().equals("ARCHIVED")) {
      throw conflict();
    }
    if (policy.draft() == null || policy.draft().equals(policy.settings())) {
      return policy;
    }
    long version = policy.version() + 1;
    jdbc.sql(
            """
            INSERT INTO automation_policy_publication(organization_id,policy_id,version,settings,
              author_id) VALUES (:org,:policy,:version,CAST(:settings AS jsonb),:author)
            """)
        .param("org", scope.organizationId())
        .param("policy", id)
        .param("version", version)
        .param("settings", json.encode(policy.draft()))
        .param("author", scope.subjectId())
        .update();
    jdbc.sql(
            """
            UPDATE automation_policy SET version=:version,revision=revision+1,
              status=CASE WHEN status='DRAFT' THEN 'ACTIVE' ELSE status END
            WHERE organization_id=:org AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("id", id)
        .param("version", version)
        .update();
    changed(scope, id, policy.revision() + 1, "POLICY_PUBLISHED");
    return read(scope, id, false);
  }

  public void requirePublicationPermissions(Scope scope, UUID policyId) {
    if (scope.accountId() != null) {
      throw new BusinessException(
          "COMPANY_SCOPE_REQUIRED", 422, "Публикация выполняется в организации");
    }
    authorization.requireLocked(scope, Set.of("organization.policy.manage"));
    read(scope, policyId, true);
    List<UUID> accounts =
        jdbc.sql(
                """
                SELECT DISTINCT account_id FROM automation_assignment
                WHERE organization_id=:org AND policy_id=:policy AND NOT detached ORDER BY account_id
                """)
            .param("org", scope.organizationId())
            .param("policy", policyId)
            .query(UUID.class)
            .list();
    authorization.requireAcrossAccounts(scope, accounts, Set.of("policy.assign", "finance.read"));
  }

  @Transactional
  public Policy changeStatus(Scope scope, UUID id, String status, long expectedRevision) {
    authorization.require(scope, "organization.policy.manage");
    if (status.equals("ACTIVE")) {
      requirePublicationPermissions(scope, id);
    }
    Policy policy = read(scope, id, true);
    if (policy.revision() != expectedRevision
        || policy.status().equals("ARCHIVED")
        || !Set.of("ACTIVE", "PAUSED", "ARCHIVED").contains(status)
        || (policy.version() == 0 && !status.equals("ARCHIVED"))) {
      throw conflict();
    }
    if (!policy.status().equals(status)) {
      jdbc.sql(
              """
              UPDATE automation_policy SET status=:status,revision=revision+1
              WHERE organization_id=:org AND id=:id
              """)
          .param("org", scope.organizationId())
          .param("id", id)
          .param("status", status)
          .update();
      changed(scope, id, policy.revision() + 1, "POLICY_" + status);
    }
    return read(scope, id, false);
  }

  public List<Assignment> assignments(Scope scope, int limit, int offset) {
    authorization.require(scope, "policy.read");
    if (limit < 1 || limit > 200 || offset < 0) {
      throw new IllegalArgumentException("Invalid page");
    }
    return jdbc.sql(
            """
            SELECT * FROM automation_assignment WHERE organization_id=:org AND account_id=:account
            AND NOT detached ORDER BY id LIMIT :limit OFFSET :offset
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("limit", limit)
        .param("offset", offset)
        .query(this::mapAssignment)
        .list();
  }

  public Assignment getAssignment(Scope scope, UUID id) {
    authorization.require(scope, "policy.read");
    return jdbc.sql(
            """
            SELECT * FROM automation_assignment WHERE organization_id=:org AND account_id=:account
              AND id=:id
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("id", id)
        .query(this::mapAssignment)
        .optional()
        .orElseThrow(
            () -> new BusinessException("ASSIGNMENT_NOT_FOUND", 404, "Назначение недоступно"));
  }

  public Page<Assignment> assignmentPage(
      Scope scope, int page, int size, UUID policyId, String kind, String mode) {
    return assignmentPage(scope, page, size, policyId, kind, mode, null);
  }

  public Page<Assignment> assignmentPage(
      Scope scope, int page, int size, UUID policyId, String kind, String mode, UUID offerId) {
    authorization.require(scope, "policy.read");
    long offset = Page.offset(page, size);
    if (kind == null
        || mode == null
        || (!kind.isEmpty() && !Set.of("ACCOUNT", "CATEGORY", "OFFER").contains(kind))
        || (!mode.isEmpty() && !Set.of("PREVIEW", "MANUAL", "AUTO").contains(mode))) {
      throw new IllegalArgumentException("Invalid assignment filter");
    }
    var categories = marketplace.categoryPathsProjection(scope);
    String filter =
        """
        WHERE organization_id=:org AND account_id=:account
          AND NOT detached AND (CAST(:policy AS uuid) IS NULL OR policy_id=:policy)
          AND (:kind='' OR scope_kind=:kind) AND (:mode='' OR mode=:mode)
          AND (CAST(:offer AS uuid) IS NULL OR EXISTS(SELECT 1 FROM (%s) o
            WHERE o.organization_id=:org AND o.account_id=:account AND o.id=:offer
              AND ((scope_kind='ACCOUNT' AND target_id=:account)
                OR (scope_kind='OFFER' AND target_id=o.id)
                OR (scope_kind='CATEGORY' AND target_id=ANY(o.category_path)))))
        """
            .formatted(categories.sql());
    long total =
        jdbc.sql("SELECT count(*) FROM automation_assignment " + filter)
            .params(categories.parameters())
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("policy", policyId)
            .param("kind", kind)
            .param("mode", mode)
            .param("offer", offerId)
            .query(Long.class)
            .single();
    var items =
        jdbc.sql(
                "SELECT * FROM automation_assignment "
                    + filter
                    + " ORDER BY scope_kind,target_id,id LIMIT :size OFFSET :offset")
            .params(categories.parameters())
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("policy", policyId)
            .param("kind", kind)
            .param("mode", mode)
            .param("offer", offerId)
            .param("size", size)
            .param("offset", offset)
            .query(this::mapAssignment)
            .list();
    return new Page<>(items, total, page, size);
  }

  @Transactional
  public Assignment pauseAssignment(Scope scope, UUID id, long expectedRevision, boolean pause) {
    authorization.requireLocked(scope, Set.of(pause ? "automation.pause" : "automation.resume"));
    lockAssignments(scope);
    Assignment assignment =
        jdbc.sql(
                """
                SELECT * FROM automation_assignment WHERE organization_id=:org AND account_id=:account
                  AND id=:id FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("id", id)
            .query(this::mapAssignment)
            .optional()
            .orElseThrow(
                () -> new BusinessException("ASSIGNMENT_NOT_FOUND", 404, "Назначение недоступно"));
    if (assignment.detached() || assignment.revision() != expectedRevision) {
      throw conflict();
    }
    if (assignment.paused() != pause) {
      jdbc.sql(
              """
              UPDATE automation_assignment SET paused=:pause,revision=revision+1
              WHERE organization_id=:org AND account_id=:account AND id=:id
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("id", id)
          .param("pause", pause)
          .update();
      changed(scope, id, expectedRevision + 1, pause ? "ASSIGNMENT_PAUSED" : "ASSIGNMENT_RESUMED");
    }
    return new Assignment(
        id,
        assignment.policyId(),
        assignment.scope(),
        assignment.targetId(),
        assignment.mode(),
        assignment.enabled(),
        pause,
        assignment.revision() + (assignment.paused() == pause ? 0 : 1),
        assignment.regularReferences(),
        assignment.temporaryReferences(),
        false);
  }

  /** Specificity is resolved before activity, including paused or disabled child assignments. */
  public SqlReadProjection effectiveAssignmentProjection(Scope scope) {
    authorization.require(scope, "policy.read");
    var categories = marketplace.categoryPathsProjection(scope);
    var parameters = new java.util.LinkedHashMap<String, Object>(categories.parameters());
    parameters.put("policyOrganization", scope.requireOrganization());
    parameters.put("policyAccount", scope.requireAccount());
    return new SqlReadProjection(
        """
        SELECT o.id AS offer_id,selected.id AS assignment_id,selected.policy_id,
          p.name AS policy_name,selected.mode,selected.enabled,selected.paused,
          p.version AS policy_version,p.status AS policy_status
        FROM (%s) o JOIN LATERAL (
          SELECT a.* FROM automation_assignment a WHERE a.organization_id=o.organization_id
            AND a.account_id=o.account_id AND NOT a.detached AND ((a.scope_kind='ACCOUNT' AND a.target_id=o.account_id AND o.category_path IS NOT NULL)
              OR (a.scope_kind='OFFER' AND a.target_id=o.id)
              OR (a.scope_kind='CATEGORY' AND a.target_id=ANY(o.category_path)))
          ORDER BY CASE a.scope_kind WHEN 'OFFER' THEN 0 WHEN 'CATEGORY' THEN 1 ELSE 2 END,
            array_position(o.category_path,a.target_id) NULLS LAST LIMIT 1
        ) selected ON true JOIN automation_policy p
          ON p.organization_id=o.organization_id AND p.id=selected.policy_id
        WHERE o.organization_id=:policyOrganization AND o.account_id=:policyAccount
        """
            .formatted(categories.sql()),
        parameters);
  }

  public ActivePolicy resolveForOffer(Scope scope, UUID offerId) {
    authorization.require(scope, "policy.read");
    jdbc.sql("SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR SHARE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    boolean direct =
        jdbc.sql(
                """
                SELECT EXISTS(SELECT 1 FROM automation_assignment WHERE organization_id=:org
                  AND account_id=:account AND scope_kind='OFFER' AND target_id=:offer AND NOT detached)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("offer", offerId)
            .query(Boolean.class)
            .single();
    return resolveForOffer(
        scope, offerId, direct ? List.of() : marketplace.categoryAncestors(scope, offerId));
  }

  private ActivePolicy resolveForOffer(Scope scope, UUID offerId, List<UUID> categoryAncestors) {
    authorization.require(scope, "policy.read");
    if (categoryAncestors.size() > 64) {
      throw new BusinessException("CATEGORY_DEPTH", 422, "Иерархия категорий слишком глубока");
    }
    List<UUID> targets = new java.util.ArrayList<>(categoryAncestors);
    targets.add(offerId);
    targets.add(scope.requireAccount());
    List<Assignment> matches =
        jdbc.sql(
                """
                SELECT * FROM automation_assignment WHERE organization_id=:org AND account_id=:account
                  AND NOT detached AND target_id IN (:targets)
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("targets", targets)
            .query(this::mapAssignment)
            .list();
    PolicyResolver.Resolution selected = resolve(matches, categoryAncestors);
    if (selected.selected() == null) {
      throw new BusinessException(selected.reason(), 409, "Действующее назначение не определено");
    }
    Assignment assignment =
        matches.stream()
            .filter(a -> a.id().equals(selected.selected().id()))
            .findFirst()
            .orElseThrow();
    Policy policy = read(scope, assignment.policyId(), false);
    if (policy.settings() == null) {
      throw new BusinessException("POLICY_UNPUBLISHED", 409, "Политика не опубликована");
    }
    return new ActivePolicy(
        policy.id(),
        policy.version(),
        assignment.id(),
        assignment.revision(),
        assignment.mode(),
        selected.active() && policy.status().equals("ACTIVE"),
        bind(policy.settings(), assignment.regularReferences(), assignment.temporaryReferences()));
  }

  private static PolicyResolver.Resolution resolve(
      List<Assignment> matches, List<UUID> categoryAncestors) {
    List<PolicyResolver.Assignment> resolved =
        matches.stream()
            .map(
                a -> {
                  PolicyResolver.ScopeKind kind = PolicyResolver.ScopeKind.valueOf(a.scope());
                  int distance =
                      kind == PolicyResolver.ScopeKind.CATEGORY
                          ? categoryAncestors.indexOf(a.targetId())
                          : 0;
                  PolicyResolver.Status status =
                      !a.enabled()
                          ? PolicyResolver.Status.DISABLED
                          : a.paused()
                              ? PolicyResolver.Status.PAUSED
                              : PolicyResolver.Status.ACTIVE;
                  return new PolicyResolver.Assignment(
                      a.id(), a.policyId(), kind, a.targetId(), distance, status, a.revision());
                })
            .toList();
    return new PolicyResolver().resolve(resolved);
  }

  public record InheritedRule(
      UUID assignmentId,
      long assignmentRevision,
      UUID policyId,
      String policyName,
      long policyVersion,
      long policyRevision,
      String scope,
      UUID targetId,
      String mode,
      String status) {}

  public record DetachReview(
      UUID assignmentId,
      long assignmentRevision,
      InheritedRule inherited,
      String reason,
      String digest) {}

  /** Previews the exact ancestor binding; more-specific child assignments remain unchanged. */
  public DetachReview reviewDetach(Scope scope, UUID id, long expectedRevision) {
    authorization.requireLocked(scope, Set.of("policy.assign", "finance.read"));
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    lockAssignments(scope);
    Assignment assignment = getAssignment(scope, id);
    if (assignment.detached() || assignment.revision() != expectedRevision) {
      throw conflict();
    }
    List<UUID> ancestors =
        switch (assignment.scope()) {
          case "ACCOUNT" -> List.of();
          case "OFFER" -> marketplace.categoryAncestors(scope, assignment.targetId());
          case "CATEGORY" -> marketplace.categoryAncestry(scope, assignment.targetId());
          default -> throw new IllegalStateException("Unexpected assignment scope");
        };
    List<UUID> targets = new java.util.ArrayList<>(ancestors);
    targets.add(scope.requireAccount());
    List<Assignment> matches =
        jdbc.sql(
                """
                SELECT * FROM automation_assignment WHERE organization_id=:org AND account_id=:account
                  AND NOT detached AND id<>:id AND target_id IN (:targets)
                  AND scope_kind IN ('CATEGORY','ACCOUNT')
                """)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("id", id)
            .param("targets", targets)
            .query(this::mapAssignment)
            .list();
    PolicyResolver.Resolution future = resolve(matches, ancestors);
    if (future.selected() == null && !future.reason().equals("NO_ASSIGNMENT")) {
      throw new BusinessException(
          future.reason(), 409, "Нельзя однозначно определить следующее правило");
    }
    InheritedRule inherited = null;
    String reason = future.reason();
    if (future.selected() != null) {
      Assignment next =
          matches.stream()
              .filter(a -> a.id().equals(future.selected().id()))
              .findFirst()
              .orElseThrow();
      Policy policy = read(scope, next.policyId(), true);
      String status = policy.status().equals("ACTIVE") ? future.reason() : policy.status();
      inherited =
          new InheritedRule(
              next.id(),
              next.revision(),
              policy.id(),
              policy.name(),
              policy.version(),
              policy.revision(),
              next.scope(),
              next.targetId(),
              next.mode(),
              status);
      reason = status;
    }
    record Basis(UUID assignmentId, long revision, List<UUID> ancestors, InheritedRule inherited) {}
    String digest;
    try {
      digest =
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(
                          json.encode(new Basis(id, expectedRevision, ancestors, inherited))
                              .getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is required by Java", error);
    }
    return new DetachReview(id, expectedRevision, inherited, reason, digest);
  }

  Assignment detach(Scope scope, UUID id, long revision, String expectedDigest) {
    DetachReview review = reviewDetach(scope, id, revision);
    if (expectedDigest == null || !review.digest().equals(expectedDigest)) {
      throw new BusinessException(
          "ASSIGNMENT_INHERITANCE_CHANGED",
          409,
          "Наследуемое правило изменилось. Повторите предварительный просмотр");
    }
    jdbc.sql(
            """
            UPDATE automation_assignment SET detached=true,enabled=false,paused=false,revision=revision+1
            WHERE organization_id=:org AND account_id=:account AND id=:id AND revision=:revision
            """)
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .param("id", id)
        .param("revision", revision)
        .update();
    changed(scope, id, revision + 1, "ASSIGNMENT_DETACHED");
    return getAssignment(scope, id);
  }

  public ActivePolicy scenarioPolicy(
      Scope scope, UUID assignmentId, long expectedRevision, List<UUID> offerIds) {
    authorization.require(scope, "automation.manage");
    if (offerIds.isEmpty()
        || offerIds.size() > 1000
        || offerIds.stream().distinct().count() != offerIds.size()) {
      throw new IllegalArgumentException("An episode needs 1..1000 distinct offers");
    }
    Assignment assignment =
        jdbc.sql(
                """
                SELECT * FROM automation_assignment WHERE organization_id=:org AND account_id=:account
                  AND id=:id FOR SHARE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("id", assignmentId)
            .query(this::mapAssignment)
            .optional()
            .orElseThrow(
                () -> new BusinessException("ASSIGNMENT_NOT_FOUND", 404, "Назначение недоступно"));
    if (assignment.detached()
        || assignment.revision() != expectedRevision
        || !assignment.enabled()
        || assignment.paused()
        || assignment.mode().equals("PREVIEW")) {
      throw new BusinessException("ASSIGNMENT_NOT_ACTIVE", 409, "Назначение не допускает запуск");
    }
    var categories = marketplace.categoryPathsProjection(scope);
    long matching =
        jdbc.sql(
                """
                SELECT count(*) FROM (%s) o CROSS JOIN LATERAL (
                  SELECT a.id FROM automation_assignment a WHERE a.organization_id=o.organization_id
                    AND a.account_id=o.account_id AND NOT a.detached AND ((a.scope_kind='ACCOUNT' AND a.target_id=o.account_id AND o.category_path IS NOT NULL)
                      OR (a.scope_kind='OFFER' AND a.target_id=o.id)
                      OR (a.scope_kind='CATEGORY' AND a.target_id=ANY(o.category_path)))
                  ORDER BY CASE a.scope_kind WHEN 'OFFER' THEN 0 WHEN 'CATEGORY' THEN 1 ELSE 2 END,
                    array_position(o.category_path,a.target_id) NULLS LAST LIMIT 1
                ) selected WHERE o.organization_id=:org AND o.account_id=:account
                  AND o.id IN (:offers) AND selected.id=:assignment
                """
                    .formatted(categories.sql()))
            .params(categories.parameters())
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("offers", offerIds)
            .param("assignment", assignmentId)
            .query(Long.class)
            .single();
    if (matching != offerIds.size()) {
      throw new BusinessException(
          "SCENARIO_SCOPE_CHANGED", 409, "Полный охват назначения не подтверждён");
    }
    Policy policy = read(scope, assignment.policyId(), true);
    if (!policy.status().equals("ACTIVE")
        || policy.settings() == null
        || policy.settings().temporaryRule() == null) {
      throw new BusinessException(
          "TEMPORARY_RULE_REQUIRED", 422, "Нужна опубликованная временная ветвь");
    }
    return new ActivePolicy(
        policy.id(),
        policy.version(),
        assignment.id(),
        assignment.revision(),
        assignment.mode(),
        true,
        bind(policy.settings(), assignment.regularReferences(), assignment.temporaryReferences()));
  }

  @Transactional
  public Assignment assign(Scope scope, AssignmentInput input) {
    authorization.requireLocked(scope, Set.of("policy.assign", "finance.read"));
    lockAssignments(scope);
    Policy policy = read(scope, input.policyId(), true);
    if (policy.version() == 0 || policy.status().equals("ARCHIVED")) {
      throw new BusinessException("POLICY_UNPUBLISHED", 409, "Опубликуйте политику");
    }
    bind(policy.settings(), input.regularReferences(), input.temporaryReferences());
    if (input.enabled() && input.mode().equals("AUTO")) {
      authorization.requireLocked(scope, Set.of("automation.manage", "finance.read"));
    }
    if (input.scope().equals("OFFER")) {
      jdbc.sql(
              """
              SELECT id FROM marketplace_offer WHERE organization_id=:org AND account_id=:account
                AND id=:offer
              """)
          .param("org", scope.organizationId())
          .param("account", scope.requireAccount())
          .param("offer", input.targetId())
          .query(UUID.class)
          .optional()
          .orElseThrow(() -> new BusinessException("OFFER_NOT_FOUND", 404, "Товар недоступен"));
    }
    if (input.scope().equals("CATEGORY")) {
      marketplace.categoryAncestry(scope, input.targetId());
    }
    UUID target = input.scope().equals("ACCOUNT") ? scope.requireAccount() : input.targetId();
    var previous =
        jdbc.sql(
                """
                SELECT * FROM automation_assignment WHERE organization_id=:org AND account_id=:account
                  AND scope_kind=:kind AND target_id=:target FOR UPDATE
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .param("kind", input.scope())
            .param("target", target)
            .query(this::mapAssignment)
            .optional();
    long revision = previous.map(Assignment::revision).orElse(0L);
    long currentRevision = previous.filter(a -> !a.detached()).map(Assignment::revision).orElse(0L);
    if (currentRevision != input.expectedRevision()) {
      throw conflict();
    }
    UUID id = previous.map(Assignment::id).orElseGet(UUID::randomUUID);
    if (previous.isEmpty()) {
      jdbc.sql(
              """
              INSERT INTO automation_assignment(organization_id,account_id,id,policy_id,scope_kind,
                target_id,mode,enabled,paused,revision,regular_references,temporary_references)
              VALUES (:org,:account,:id,:policy,:kind,:target,:mode,:enabled,false,1,
                CAST(:regular AS jsonb),CAST(:temporary AS jsonb))
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("id", id)
          .param("policy", input.policyId())
          .param("kind", input.scope())
          .param("target", target)
          .param("mode", input.mode())
          .param("enabled", input.enabled())
          .param("regular", json.encode(input.regularReferences()))
          .param("temporary", json.encode(input.temporaryReferences()))
          .update();
    } else {
      jdbc.sql(
              """
              UPDATE automation_assignment SET policy_id=:policy,mode=:mode,enabled=:enabled,
                paused=CASE WHEN detached THEN false ELSE paused END,detached=false,
                revision=revision+1,regular_references=CAST(:regular AS jsonb),
                temporary_references=CAST(:temporary AS jsonb)
              WHERE organization_id=:org AND account_id=:account AND id=:id
              """)
          .param("org", scope.organizationId())
          .param("account", scope.accountId())
          .param("id", id)
          .param("policy", input.policyId())
          .param("mode", input.mode())
          .param("regular", json.encode(input.regularReferences()))
          .param("temporary", json.encode(input.temporaryReferences()))
          .param("enabled", input.enabled())
          .update();
    }
    changed(scope, id, revision + 1, "ASSIGNMENT_CHANGED");
    return new Assignment(
        id,
        input.policyId(),
        input.scope(),
        target,
        input.mode(),
        input.enabled(),
        previous.filter(a -> !a.detached()).map(Assignment::paused).orElse(false),
        revision + 1,
        input.regularReferences(),
        input.temporaryReferences(),
        false);
  }

  private Policy read(Scope scope, UUID id, boolean lock) {
    return jdbc.sql(
            """
            SELECT p.*,v.settings::text AS published_settings,
              assignment_summary.assignments_count,assignment_summary.assignment_scopes
            FROM automation_policy p LEFT JOIN automation_policy_publication v
              ON v.organization_id=p.organization_id AND v.policy_id=p.id AND v.version=p.version
            """
                + ASSIGNMENT_SUMMARY_SQL
                + """
                WHERE p.organization_id=:org AND p.id=:id
                """
                + (lock ? " FOR UPDATE OF p" : ""))
        .param("org", scope.requireOrganization())
        .param("id", id)
        .param("assignmentAccount", assignmentAccount(scope))
        .query(this::mapPolicy)
        .optional()
        .orElseThrow(() -> new BusinessException("POLICY_NOT_FOUND", 404, "Политика недоступна"));
  }

  private String assignmentAccount(Scope scope) {
    return scope.accountId() != null && authorization.hasPermission(scope, "policy.read")
        ? scope.accountId().toString()
        : "";
  }

  private Policy mapPolicy(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    String published = rs.getString("published_settings");
    PolicySettings settings =
        published == null ? null : json.decode(published, PolicySettings.class);
    PolicySettings draft = json.decode(rs.getString("draft"), PolicySettings.class);
    String scopes = rs.getString("assignment_scopes");
    return new Policy(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("description"),
        rs.getString("status"),
        rs.getLong("version"),
        rs.getLong("revision"),
        (settings == null ? draft : settings).regularRule().strategy().name(),
        rs.getObject("assignments_count", Long.class),
        scopes == null ? null : List.of(json.decode(scopes, String[].class)),
        settings,
        draft);
  }

  private Assignment mapAssignment(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Assignment(
        rs.getObject("id", UUID.class),
        rs.getObject("policy_id", UUID.class),
        rs.getString("scope_kind"),
        rs.getObject("target_id", UUID.class),
        rs.getString("mode"),
        rs.getBoolean("enabled"),
        rs.getBoolean("paused"),
        rs.getLong("revision"),
        json.decode(rs.getString("regular_references"), LocalReferences.class),
        json.decode(rs.getString("temporary_references"), LocalReferences.class),
        rs.getBoolean("detached"));
  }

  private static String ordering(String direction) {
    return switch (direction) {
      case "", "asc" -> " ASC";
      case "desc" -> " DESC";
      default -> throw new IllegalArgumentException("Invalid sort direction");
    };
  }

  private static void companyRules(PolicySettings settings) {
    companyRule(settings.regularRule());
    if (settings.temporaryRule() != null) {
      companyRule(settings.temporaryRule());
    }
  }

  private static void companyRule(PolicySettings.Rule rule) {
    if (!rule.selectedPromos().isEmpty()
        || !rule.protectedPromos().isEmpty()
        || !rule.allowedCombinations().isEmpty()
        || !rule.discoveryProfiles().isEmpty()
        || !rule.excludedPromos().isEmpty()
        || (rule.competitor() != null && rule.competitor().selectedSource() != null)) {
      throw new IllegalArgumentException("Cabinet references belong to the assignment");
    }
    if (rule.reserve() != null) {
      companyRule(rule.reserve());
    }
  }

  private static PolicySettings bind(
      PolicySettings settings, LocalReferences regular, LocalReferences temporary) {
    return new PolicySettings(
        bindRule(settings.regularRule(), regular),
        settings.temporaryRule() == null ? null : bindRule(settings.temporaryRule(), temporary),
        settings.allowedOperations(),
        settings.stockProtection(),
        settings.stability(),
        settings.schedule(),
        settings.allowProtectiveCorrection());
  }

  private static PolicySettings.Rule bindRule(PolicySettings.Rule rule, LocalReferences refs) {
    var competitor = rule.competitor();
    if (competitor != null) {
      competitor =
          new PolicySettings.Competitor(
              competitor.aggregation(),
              refs.competitorSource(),
              competitor.minimumSources(),
              competitor.segment(),
              competitor.amountOffset(),
              competitor.ratioOffset(),
              competitor.tolerance(),
              competitor.maxAgeSeconds());
      if (competitor.aggregation() == PolicySettings.Aggregation.SELECTED_SELLER
          && competitor.selectedSource() == null) {
        throw new BusinessException(
            "LOCAL_REFERENCE_REQUIRED", 422, "Не выбран конкурент назначения");
      }
    }
    return new PolicySettings.Rule(
        rule.strategy(),
        rule.priceKind(),
        rule.target(),
        rule.corridorMinimum(),
        rule.corridorMaximum(),
        rule.metric(),
        rule.unreachable(),
        competitor,
        rule.reserve() == null ? null : bindRule(rule.reserve(), refs),
        rule.promoMode(),
        refs.selectedPromos(),
        refs.protectedPromos(),
        refs.allowedCombinations(),
        refs.discoveryProfiles(),
        refs.excludedPromos());
  }

  private void changed(Scope scope, UUID id, long revision, String action) {
    audit.record(scope, action, id, "revision=" + revision);
    outbox.emit(
        scope,
        action + ":" + id + ":" + revision,
        action,
        new Changed(
            action.startsWith("ASSIGNMENT") ? "policy-assignments" : "policies", id, revision));
  }

  private static BusinessException conflict() {
    return new BusinessException("STALE_REVISION", 409, "Политика изменена или недоступна");
  }
}
