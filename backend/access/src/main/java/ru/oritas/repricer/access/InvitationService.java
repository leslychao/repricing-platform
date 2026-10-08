package ru.oritas.repricer.access;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Page;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;
import ru.oritas.repricer.platform.VaultSecretStore;

/** Single-use invitations. SQL stores the fingerprint; the mail job carries only the ID. */
@Service
public final class InvitationService {
  private static final Duration LIFETIME = Duration.ofHours(48);
  private static final SecureRandom RANDOM = new SecureRandom();
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final AccessService access;
  private final IdempotencyService idempotency;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final VaultSecretStore secrets;
  private final Clock clock;

  public InvitationService(
      JdbcClient jdbc,
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      AccessService access,
      IdempotencyService idempotency,
      JobRuntime jobs,
      JsonCodec json,
      VaultSecretStore secrets,
      Clock clock) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.authorization = authorization;
    this.access = access;
    this.idempotency = idempotency;
    this.jobs = jobs;
    this.json = json;
    this.secrets = secrets;
    this.clock = clock;
  }

  public Invitation create(
      Scope scope, String email, AccessService.Role role, Set<String> permissions, UUID requestId) {
    Offer offer = new Offer(email(email), role, Set.copyOf(permissions));
    transactions.run(scope, () -> validateOffer(scope, scope.subjectId(), offer));
    Prepared token = prepare(scope, "create", requestId);
    return transactions.run(
        scope,
        () -> {
          access.lockOrganization(scope);
          validateOffer(scope, scope.subjectId(), offer);
          return idempotency.execute(
              scope,
              "invitation.create",
              requestId,
              offer,
              Invitation.class,
              () -> insert(scope, offer, token));
        });
  }

  public Invitation reissue(Scope scope, UUID invitationId, long revision, UUID requestId) {
    transactions.run(
        scope,
        () -> {
          Details prior = details(invitationId);
          validateOffer(scope, scope.subjectId(), prior.offer());
        });
    Prepared token = prepare(scope, "reissue", requestId);
    return transactions.run(
        scope,
        () -> {
          access.lockOrganization(scope);
          Details prior = details(invitationId);
          validateOffer(scope, scope.subjectId(), prior.offer());
          return idempotency.execute(
              scope,
              "invitation.reissue",
              requestId,
              new RevisionIntent(invitationId, revision),
              Invitation.class,
              () -> {
                AccessService.requireRevision(prior.invitation().revision(), revision);
                if (prior.invitation().consumedAt() != null) {
                  throw AccessService.conflict("Приглашение уже принято");
                }
                jdbc.sql(
                        """
                        UPDATE access_invitation SET revoked_at=clock_timestamp(),revision=revision+1,
                          mail_state=CASE WHEN mail_state='READY' THEN 'CANCELLED' ELSE mail_state END
                        WHERE id=:id
                        """)
                    .param("id", invitationId)
                    .update();
                access.changed(
                    scope, "invitation.revoked", invitationId, access.nextRevision(scope));
                return insert(scope, prior.offer(), token);
              });
        });
  }

  public Invitation revoke(Scope scope, UUID invitationId, long revision, UUID requestId) {
    return transactions.run(
        scope,
        () -> {
          access.lockOrganization(scope);
          Details prior = details(invitationId);
          validateOffer(scope, scope.subjectId(), prior.offer());
          return idempotency.execute(
              scope,
              "invitation.revoke",
              requestId,
              new RevisionIntent(invitationId, revision),
              Invitation.class,
              () -> {
                AccessService.requireRevision(prior.invitation().revision(), revision);
                if (prior.invitation().consumedAt() != null) {
                  throw AccessService.conflict("Приглашение уже принято");
                }
                if (prior.invitation().revokedAt() == null) {
                  jdbc.sql(
                          """
                          UPDATE access_invitation SET revoked_at=clock_timestamp(),revision=revision+1,
                            mail_state=CASE WHEN mail_state='READY' THEN 'CANCELLED' ELSE mail_state END
                          WHERE id=:id
                          """)
                      .param("id", invitationId)
                      .update();
                  access.changed(
                      scope, "invitation.revoked", invitationId, access.nextRevision(scope));
                }
                return details(invitationId).invitation();
              });
        });
  }

  public Page<Invitation> list(Scope scope, AccessQuery query) {
    return transactions.run(
        scope,
        () -> {
          authorization.require(scope, "membership.manage");
          InvitationSelection selection = selection(scope, query);
          var items =
              jdbc.sql(
                      "SELECT i.* "
                          + selection.source()
                          + " ORDER BY "
                          + selection.order()
                          + " LIMIT :size OFFSET :offset")
                  .params(selection.parameters())
                  .param("size", query.size())
                  .param("offset", Page.offset(query.page(), query.size()))
                  .query((row, index) -> invitation(row))
                  .list();
          long total =
              jdbc.sql("SELECT count(*) " + selection.source())
                  .params(selection.parameters())
                  .query(Long.class)
                  .single();
          return new Page<>(items, total, query.page(), query.size());
        });
  }

  public TableExportSource.Projection projection(Scope scope, AccessQuery query, List<UUID> ids) {
    authorization.require(scope, "membership.manage");
    InvitationSelection selection = selection(scope, query);
    Map<String, Object> parameters = new LinkedHashMap<>(selection.parameters());
    String source = selection.source();
    if (!ids.isEmpty()) {
      source += " AND i.id IN (:ids)";
      parameters.put("ids", ids);
    }
    return new TableExportSource.Projection(
        "SELECT i.id AS canonical_id,"
            + "jsonb_build_array(i.email,i.role,i.expires_at::text,"
            + invitationState()
            + ",i.mail_state) AS cells "
            + source
            + " ORDER BY "
            + selection.order(),
        parameters,
        List.of(
            new TableExportSource.Column("email", "Email", false),
            new TableExportSource.Column("role", "Роль", false),
            new TableExportSource.Column("expires", "Действует до", false),
            new TableExportSource.Column("status", "Состояние", false),
            new TableExportSource.Column("mail", "Доставка письма", false)));
  }

  private InvitationSelection selection(Scope scope, AccessQuery query) {
    query.requireStatuses(Set.of("PENDING", "ACCEPTED", "REVOKED", "EXPIRED"));
    Map<String, Object> parameters = new LinkedHashMap<>(query.parameters());
    parameters.put("org", scope.requireOrganization());
    // A nullable account is represented in SQL, not in Projection's immutable parameter map.
    String account = scope.accountId() == null ? "i.account_id IS NULL" : "i.account_id=:account";
    if (scope.accountId() != null) {
      parameters.put("account", scope.accountId());
    }
    String source =
        "FROM access_invitation i WHERE i.organization_id=:org AND "
            + account
            + " AND (:search='' OR position(lower(:search) in lower(concat_ws('"
            + " ',i.email,i.role)))>0) AND (:status='' OR "
            + invitationState()
            + "=:status)";
    return new InvitationSelection(
        source,
        parameters,
        query.order(
            Map.of(
                "",
                "i.created_at",
                "id",
                "i.id",
                "email",
                "i.email",
                "role",
                "i.role",
                "expires",
                "i.expires_at",
                "status",
                invitationState(),
                "mail",
                "i.mail_state"),
            "i.id"));
  }

  private static String invitationState() {
    return "CASE WHEN i.consumed_at IS NOT NULL THEN 'ACCEPTED'"
        + " WHEN i.revoked_at IS NOT NULL THEN 'REVOKED'"
        + " WHEN i.expires_at<=statement_timestamp() THEN 'EXPIRED' ELSE 'PENDING' END";
  }

  private record InvitationSelection(String source, Map<String, Object> parameters, String order) {}

  public Accepted accept(UUID actor, String token, UUID requestId) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) {
      throw AccessService.invalid("Недействительное приглашение");
    }
    String fingerprint = IdempotencyService.sha256(token.getBytes(StandardCharsets.UTF_8));
    Scope self = new Scope(null, null, actor);
    Target target =
        transactions.run(
            self,
            () -> {
              jdbc.sql("SELECT set_config('app.invitation_hash',:hash,true)")
                  .param("hash", fingerprint)
                  .query(String.class)
                  .single();
              return jdbc.sql(
                      """
                      SELECT id,organization_id,account_id FROM access_invitation WHERE token_hash=:hash
                      """)
                  .param("hash", fingerprint)
                  .query(
                      (row, index) ->
                          new Target(
                              row.getObject("id", UUID.class),
                              row.getObject("organization_id", UUID.class),
                              row.getObject("account_id", UUID.class)))
                  .optional()
                  .orElseThrow(AccessService::notFound);
            });
    Scope scope = new Scope(target.organizationId(), target.accountId(), actor);
    return transactions.runService(
        scope,
        Set.of("access.invitation.accept"),
        () -> {
          access.lockOrganization(scope);
          String confirmedEmail =
              jdbc.sql(
                      """
                      SELECT email FROM access_user WHERE id=:id AND email_verified
                      """)
                  .param("id", actor)
                  .query(String.class)
                  .optional()
                  .orElseThrow(AccessService::denied);
          Details invitation = details(target.id());
          if (!invitation.invitation().email().equalsIgnoreCase(confirmedEmail)
              || !invitation.fingerprint().equals(fingerprint)) {
            throw AccessService.denied();
          }
          if (invitation.invitation().consumedAt() != null) {
            authorization.requireMembership(scope);
          }
          return idempotency.execute(
              scope,
              "invitation.accept",
              requestId,
              target.id(),
              Accepted.class,
              () -> consume(scope, invitation));
        });
  }

  private Accepted consume(Scope scope, Details details) {
    Invitation invitation = details.invitation();
    if (invitation.revokedAt() != null
        || !clock.instant().isBefore(invitation.expiresAt())
        || invitation.consumedAt() != null) {
      throw AccessService.conflict("Приглашение истекло, отозвано или уже принято");
    }
    validateOffer(scope, details.inviterId(), details.offer());
    AccessService.Member grantor = access.member(scope, details.inviterId());
    if (grantor.revision() != details.inviterMembershipRevision()) {
      throw AccessService.conflict("Права пригласившего изменились. Требуется новое приглашение");
    }
    if (scope.accountId() != null && !authorization.isOwner(scope, details.inviterId())) {
      AccessService.AccountAccess assignment =
          access.findAssignment(scope, details.inviterId()).orElseThrow(AccessService::denied);
      if (!Long.valueOf(assignment.revision()).equals(details.inviterAccountRevision())) {
        throw AccessService.conflict("Права пригласившего изменились. Требуется новое приглашение");
      }
    }
    var existingMember = access.findMember(scope, scope.subjectId());
    if (existingMember.isPresent()) {
      AccessService.Member member = existingMember.orElseThrow();
      if (!member.active() || revokedAfter(member.revokedRevision(), details.issuedRevision())) {
        throw AccessService.conflict("Приглашение не может восстановить отозванное членство");
      }
    } else {
      jdbc.sql(
              """
              INSERT INTO access_membership(id,organization_id,subject_id,role)
              VALUES (:id,:org,:subject,'MEMBER')
              """)
          .param("id", UUID.randomUUID())
          .param("org", scope.organizationId())
          .param("subject", scope.subjectId())
          .update();
    }
    AccessService.Member member = access.member(scope, scope.subjectId());
    UUID assignmentId = null;
    if (scope.accountId() != null) {
      if (member.role().equals("OWNER")) {
        throw AccessService.conflict("Владелец не получает кабинетную роль через приглашение");
      }
      var existing = access.findAssignment(scope, scope.subjectId());
      if (existing.isPresent()) {
        AccessService.AccountAccess assignment = existing.orElseThrow();
        if (!assignment.active()
            || revokedAfter(assignment.revokedRevision(), details.issuedRevision())
            || assignment.role() != invitation.role()
            || !assignment.permissions().equals(invitation.permissions())) {
          throw AccessService.conflict("Назначение уже существует с другими условиями");
        }
        assignmentId = assignment.id();
      } else {
        assignmentId = UUID.randomUUID();
        jdbc.sql(
                """
                INSERT INTO access_account_permission(id,organization_id,account_id,subject_id,
                  role,permissions,membership_generation)
                VALUES (:id,:org,:account,:subject,:role,CAST(:permissions AS text[]),:generation)
                """)
            .param("id", assignmentId)
            .param("org", scope.organizationId())
            .param("account", scope.accountId())
            .param("subject", scope.subjectId())
            .param("role", invitation.role().name())
            .param("permissions", AccessService.sqlPermissions(invitation.permissions()))
            .param("generation", member.accessGeneration())
            .update();
      }
    }
    jdbc.sql(
            """
            UPDATE access_invitation SET consumed_at=clock_timestamp(),consumed_by=:subject,
              revision=revision+1 WHERE id=:id AND consumed_at IS NULL
            """)
        .param("id", invitation.id())
        .param("subject", scope.subjectId())
        .update();
    long revision = access.nextRevision(scope);
    access.changed(scope, "invitation.accepted", invitation.id(), revision);
    return new Accepted(scope.organizationId(), scope.accountId(), member.id(), assignmentId);
  }

  private Invitation insert(Scope scope, Offer offer, Prepared token) {
    long revision = access.nextRevision(scope);
    UUID id = UUID.randomUUID();
    AccessService.Member grantor = access.member(scope, scope.subjectId());
    Long accountRevision =
        scope.accountId() == null || authorization.isOwner(scope)
            ? null
            : access.findAssignment(scope, scope.subjectId()).orElseThrow().revision();
    Instant expires = clock.instant().plus(LIFETIME);
    jdbc.sql(
            """
            INSERT INTO access_invitation(id,organization_id,account_id,inviter_id,email,token_hash,
              token_path,token_version,issued_revision,inviter_membership_revision,
              inviter_account_revision,role,permissions,expires_at)
            VALUES (:id,:org,:account,:inviter,:email,:hash,:path,1,:issued,:memberRevision,
              :accountRevision,:role,CAST(:permissions AS text[]),:expires)
            """)
        .param("id", id)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .param("inviter", scope.subjectId())
        .param("email", offer.email())
        .param("hash", token.fingerprint())
        .param("path", token.path())
        .param("issued", revision)
        .param("memberRevision", grantor.revision())
        .param("accountRevision", accountRevision)
        .param("role", offer.role().name())
        .param("permissions", AccessService.sqlPermissions(offer.permissions()))
        .param("expires", Timestamp.from(expires))
        .update();
    jobs.submit(scope, "INVITATION_MAIL", id.toString(), json.encode(new MailJob(id)));
    access.changed(scope, "invitation.created", id, revision);
    return details(id).invitation();
  }

  private Prepared prepare(Scope scope, String operation, UUID requestId) {
    if (requestId == null) {
      throw AccessService.invalid("Нужен ключ запроса");
    }
    byte[] entropy = new byte[32];
    RANDOM.nextBytes(entropy);
    String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    String path =
        "repricer/invitations/"
            + scope.requireOrganization()
            + "/"
            + (scope.accountId() == null ? "organization" : scope.accountId())
            + "/"
            + scope.subjectId()
            + "/"
            + operation
            + "/"
            + requestId;
    String stored = secrets.putIfAbsent(path, Map.of("token", raw)).get("token");
    if (stored == null || !stored.matches("[A-Za-z0-9_-]{43}")) {
      throw new IllegalStateException("Invalid invitation secret envelope");
    }
    return new Prepared(path, IdempotencyService.sha256(stored.getBytes(StandardCharsets.UTF_8)));
  }

  private void validateOffer(Scope scope, UUID grantor, Offer offer) {
    if (offer.role() == null
        || ((scope.accountId() == null) != (offer.role() == AccessService.Role.MEMBER))
        || (offer.role() == AccessService.Role.MEMBER && !offer.permissions().isEmpty())) {
      throw AccessService.invalid("Приглашение должно соответствовать выбранной области");
    }
    authorization.requireDelegation(scope, grantor, offer.role().name(), offer.permissions());
  }

  private Details details(UUID id) {
    return jdbc.sql("SELECT * FROM access_invitation WHERE id=:id")
        .param("id", id)
        .query(
            (row, index) ->
                new Details(
                    invitation(row),
                    row.getObject("inviter_id", UUID.class),
                    row.getString("token_hash"),
                    row.getLong("issued_revision"),
                    row.getLong("inviter_membership_revision"),
                    row.getObject("inviter_account_revision", Long.class)))
        .optional()
        .orElseThrow(AccessService::notFound);
  }

  private static Invitation invitation(ResultSet row) throws SQLException {
    Timestamp consumed = row.getTimestamp("consumed_at");
    Timestamp revoked = row.getTimestamp("revoked_at");
    return new Invitation(
        row.getObject("id", UUID.class),
        row.getString("email"),
        AccessService.Role.valueOf(row.getString("role")),
        Set.of((String[]) row.getArray("permissions").getArray()),
        row.getTimestamp("expires_at").toInstant(),
        consumed == null ? null : consumed.toInstant(),
        revoked == null ? null : revoked.toInstant(),
        row.getLong("revision"),
        row.getString("mail_state"));
  }

  private static boolean revokedAfter(Long revoked, long issued) {
    return revoked != null && revoked >= issued;
  }

  private static String email(String email) {
    if (email == null
        || email.length() > 254
        || !email.matches(
            "[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?\\.[A-Za-z]{2,}")) {
      throw AccessService.invalid("Введите корректный email");
    }
    return email.toLowerCase(Locale.ROOT);
  }

  public record Invitation(
      UUID id,
      String email,
      AccessService.Role role,
      Set<String> permissions,
      Instant expiresAt,
      Instant consumedAt,
      Instant revokedAt,
      long revision,
      String mailState) {
    public Invitation {
      permissions = Set.copyOf(permissions);
    }
  }

  public record Accepted(
      UUID organizationId, UUID accountId, UUID membershipId, UUID accountAccessId) {}

  public record MailJob(UUID invitationId) {}

  private record Offer(String email, AccessService.Role role, Set<String> permissions) {}

  private record RevisionIntent(UUID id, long revision) {}

  private record Prepared(String path, String fingerprint) {}

  private record Target(UUID id, UUID organizationId, UUID accountId) {}

  private record Details(
      Invitation invitation,
      UUID inviterId,
      String fingerprint,
      long issuedRevision,
      long inviterMembershipRevision,
      Long inviterAccountRevision) {
    Offer offer() {
      return new Offer(invitation.email(), invitation.role(), invitation.permissions());
    }
  }
}
