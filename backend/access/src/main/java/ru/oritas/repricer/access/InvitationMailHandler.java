package ru.oritas.repricer.access;

import jakarta.mail.MessagingException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.VaultSecretStore;

/** A persisted send boundary prevents a worker restart from blindly repeating SMTP delivery. */
@Component
public final class InvitationMailHandler implements JobHandler {
  private static final Set<String> AUTHORITY = Set.of("access.invitation.mail");
  private final JdbcClient jdbc;
  private final ScopeTransactionRunner transactions;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final VaultSecretStore secrets;
  private final JavaMailSender mail;
  private final Clock clock;
  private final URI publicUrl;
  private final String from;

  public InvitationMailHandler(JdbcClient jdbc, ScopeTransactionRunner transactions,
      JobRuntime jobs, JsonCodec json, VaultSecretStore secrets, JavaMailSender mail, Clock clock,
      @Value("${repricer.public-base-url}") URI publicUrl,
      @Value("${repricer.mail.from}") String from) {
    this.jdbc = jdbc;
    this.transactions = transactions;
    this.jobs = jobs;
    this.json = json;
    this.secrets = secrets;
    this.mail = mail;
    this.clock = clock;
    this.publicUrl = publicUrl;
    this.from = from;
  }

  @Override
  public String type() {
    return "INVITATION_MAIL";
  }

  @Override
  public JobOutcome execute(JobContext context) throws MessagingException {
    UUID id = json.decode(context.payload(), InvitationService.MailJob.class).invitationId();
    Delivery delivery = transactions.runService(context.scope(), AUTHORITY, () -> {
      jobs.requireOwnership(context);
      return delivery(id);
    });
    if (delivery.state().equals("SENT") || delivery.state().equals("CANCELLED")) {
      return JobOutcome.succeeded("{}");
    }
    if (!delivery.state().equals("READY")) {
      mark(context.scope(), id, "UNKNOWN");
      return JobOutcome.blocked("MAIL_OUTCOME_UNKNOWN");
    }
    String token = secrets.read(delivery.path(), delivery.version()).get("token");
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")
        || !IdempotencyService.sha256(token.getBytes(StandardCharsets.UTF_8))
            .equals(delivery.fingerprint())) {
      return JobOutcome.blocked("INVITATION_SECRET_MISMATCH");
    }
    var message = mail.createMimeMessage();
    var helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
    helper.setFrom(from);
    helper.setTo(delivery.email());
    helper.setSubject("Приглашение в Repricer");
    helper.setText("Вас пригласили в Repricer. Войдите с подтверждённым email "
        + delivery.email() + " и примите приглашение:\n\n" + publicUrl.resolve("/")
        + "#invitation=" + token + "\n\nСрок действия: " + delivery.expiresAt()
        + ". Если вы не ожидали приглашение, проигнорируйте это письмо.", false);
    message.setHeader("Message-ID", "<invitation-" + id + "@repricer.local>");
    boolean send = transactions.runService(context.scope(), AUTHORITY, () -> {
      jobs.requireOwnership(context);
      Delivery current = delivery(id);
      if (current.revoked() || current.consumed()
          || !clock.instant().isBefore(current.expiresAt())) {
        jdbc.sql("UPDATE access_invitation SET mail_state='CANCELLED' WHERE id=:id")
            .param("id", id).update();
        return false;
      }
      return jdbc.sql("""
          UPDATE access_invitation SET mail_state='SENDING' WHERE id=:id AND mail_state='READY'
          """).param("id", id).update() == 1;
    });
    if (!send) {
      return JobOutcome.succeeded("{}");
    }
    try {
      mail.send(message);
    } catch (MailException exception) {
      mark(context.scope(), id, "UNKNOWN");
      return JobOutcome.blocked("MAIL_OUTCOME_UNKNOWN");
    }
    mark(context.scope(), id, "SENT");
    return JobOutcome.succeeded("{}");
  }

  private void mark(Scope scope, UUID id, String state) {
    transactions.runService(scope, AUTHORITY, () -> jdbc.sql("""
        UPDATE access_invitation SET mail_state=:state WHERE id=:id
          AND mail_state IN ('SENDING','UNKNOWN')
        """).param("state", state).param("id", id).update());
  }

  private Delivery delivery(UUID id) {
    return jdbc.sql("""
        SELECT email,token_path,token_version,token_hash,expires_at,mail_state,
          revoked_at,consumed_at FROM access_invitation WHERE id=:id
        """).param("id", id).query((row, index) -> {
          Timestamp revoked = row.getTimestamp("revoked_at");
          Timestamp consumed = row.getTimestamp("consumed_at");
          return new Delivery(row.getString("email"), row.getString("token_path"),
              row.getLong("token_version"), row.getString("token_hash"),
              row.getTimestamp("expires_at").toInstant(), row.getString("mail_state"),
              revoked != null, consumed != null);
        }).single();
  }

  private record Delivery(String email, String path, long version, String fingerprint,
      Instant expiresAt, String state, boolean revoked, boolean consumed) {}
}
