package ru.oritas.repricer.platform;

import java.util.Objects;
import java.util.UUID;

/** The authenticated subject and exact tenant boundary of one operation. */
public record Scope(UUID organizationId, UUID accountId, UUID subjectId) {
  public Scope {
    Objects.requireNonNull(subjectId, "subjectId");
    if (accountId != null && organizationId == null) {
      throw new IllegalArgumentException("An account requires an organization");
    }
  }

  public UUID requireOrganization() {
    if (organizationId == null) {
      throw new BusinessException("ORGANIZATION_REQUIRED", 422, "Выберите компанию");
    }
    return organizationId;
  }

  public UUID requireAccount() {
    if (accountId == null) {
      throw new BusinessException("ACCOUNT_REQUIRED", 422, "Выберите кабинет");
    }
    return accountId;
  }
}
