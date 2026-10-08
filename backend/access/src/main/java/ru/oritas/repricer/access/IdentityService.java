package ru.oritas.repricer.access;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.platform.Scope;

@Service
public final class IdentityService {
  private final JdbcClient jdbc;

  public IdentityService(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  public static UUID userId(String issuer, String subject) {
    return UUID.nameUUIDFromBytes((issuer + "\u0000" + subject).getBytes(StandardCharsets.UTF_8));
  }

  public Profile confirm(Scope scope, String issuer, String subject, String name, String email,
      boolean emailVerified, Set<String> permissions) {
    ScopeTransactionRunner.requireCurrent(scope);
    if (!scope.subjectId().equals(userId(issuer, subject))) {
      throw new IllegalArgumentException("Identity does not match its verified scope");
    }
    jdbc.sql("""
        INSERT INTO access_user(id,issuer,subject,display_name,email,email_verified)
        VALUES (:id,:issuer,:subject,:name,:email,:verified)
        ON CONFLICT(id) DO UPDATE SET display_name=EXCLUDED.display_name,
          email=EXCLUDED.email,email_verified=EXCLUDED.email_verified
        """).param("id", scope.subjectId()).param("issuer", issuer).param("subject", subject)
        .param("name", name).param("email", email).param("verified", emailVerified).update();
    List<Company> companies = jdbc.sql("""
        SELECT o.id,o.name,m.role,o.revision FROM access_organization o
        JOIN access_membership m ON m.organization_id=o.id
        WHERE m.subject_id=:subject AND m.active ORDER BY o.id
        """).param("subject", scope.subjectId())
        .query((row, index) -> new Company(row.getObject("id", UUID.class), row.getString("name"),
            row.getString("role"), row.getLong("revision"))).list();
    return new Profile(scope.subjectId(), name, email, companies, permissions);
  }

  public record Company(UUID id, String name, String role, long revision) {}

  public record Profile(UUID userId, String displayName, String email,
      List<Company> organizations, Set<String> permissions) {
    public Profile {
      organizations = List.copyOf(organizations);
      permissions = Set.copyOf(permissions);
    }
  }
}
