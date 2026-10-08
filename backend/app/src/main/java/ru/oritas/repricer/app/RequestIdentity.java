package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.IdentityService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Scope;

@Component
public final class RequestIdentity {
  public Jwt token() {
    if (SecurityContextHolder.getContext().getAuthentication().getPrincipal() instanceof Jwt jwt) {
      return jwt;
    }
    throw new BusinessException("AUTHENTICATION_REQUIRED", 401, "Войдите в приложение");
  }

  public Scope self() {
    Jwt jwt = token();
    return new Scope(
        null, null, IdentityService.userId(jwt.getIssuer().toString(), jwt.getSubject()));
  }

  public Scope scope(HttpServletRequest request) {
    return new Scope(
        identifier(request.getHeader("X-Organization-Id")),
        identifier(request.getHeader("X-Account-Id")),
        self().subjectId());
  }

  /** Download links carry an explicit scope; the same server authorization still applies. */
  public Scope downloadScope(HttpServletRequest request, UUID organizationId, UUID accountId) {
    Scope headers = scope(request);
    if (organizationId == null
        || (headers.organizationId() != null && !headers.organizationId().equals(organizationId))
        || (headers.accountId() != null && !headers.accountId().equals(accountId))) {
      throw new BusinessException("INVALID_SCOPE", 422, "Укажите область файла");
    }
    return new Scope(organizationId, accountId, headers.subjectId());
  }

  private static UUID identifier(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new BusinessException("INVALID_SCOPE", 422, "Некорректный идентификатор области");
    }
  }
}
