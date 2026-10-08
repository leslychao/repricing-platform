package ru.oritas.repricer.app;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.oritas.repricer.access.AccessQuery;
import ru.oritas.repricer.access.AccessService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.IdentityService;
import ru.oritas.repricer.access.InvitationService;
import ru.oritas.repricer.access.LogoutService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Page;

@RestController
public final class AccessController {
  private final RequestIdentity identity;
  private final IdentityService identities;
  private final AccessService access;
  private final InvitationService invitations;
  private final AuthorizationService authorization;
  private final ScopeTransactionRunner transactions;
  private final LogoutService logout;
  private final JwtDecoder decoder;

  public AccessController(
      RequestIdentity identity,
      IdentityService identities,
      AccessService access,
      InvitationService invitations,
      AuthorizationService authorization,
      ScopeTransactionRunner transactions,
      LogoutService logout,
      JwtDecoder decoder) {
    this.identity = identity;
    this.identities = identities;
    this.access = access;
    this.invitations = invitations;
    this.authorization = authorization;
    this.transactions = transactions;
    this.logout = logout;
    this.decoder = decoder;
  }

  @GetMapping("/api/v1/auth/csrf")
  public Map<String, String> csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @GetMapping("/api/v1/me")
  public IdentityService.Profile me(HttpServletRequest request) {
    var token = identity.token();
    var scope = identity.scope(request);
    IdentityService.Profile profile =
        transactions.run(
            identity.self(),
            () ->
                identities.confirm(
                    identity.self(),
                    token.getIssuer().toString(),
                    token.getSubject(),
                    token.getClaimAsString("name"),
                    token.getClaimAsString("email"),
                    Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified")),
                    Set.of()));
    Set<String> permissions =
        scope.organizationId() == null
            ? Set.of()
            : transactions.run(scope, () -> authorization.permissions(scope));
    return new IdentityService.Profile(
        profile.userId(),
        profile.displayName(),
        profile.email(),
        profile.organizations(),
        permissions);
  }

  @PostMapping("/api/v1/auth/logout")
  public ResponseEntity<Map<String, String>> logout(HttpServletRequest request) {
    String raw = request.getHeader("X-Repricer-ID-Token");
    if (raw == null || !raw.startsWith("Bearer ") || raw.length() > 16000) {
      throw new BusinessException("LOGOUT_UNCONFIRMED", 503, "Сессия выхода не подтверждена");
    }
    String encoded = raw.substring(7);
    var idToken = decoder.decode(encoded);
    if (!idToken.getSubject().equals(identity.token().getSubject())) {
      throw new BusinessException("INVALID_LOGOUT_SESSION", 403, "Недопустимая сессия выхода");
    }
    HttpHeaders headers = new HttpHeaders();
    logout
        .terminate(encoded, request.getHeader("Cookie"))
        .forEach(value -> headers.add(HttpHeaders.SET_COOKIE, value));
    headers.add(HttpHeaders.SET_COOKIE, "XSRF-TOKEN=; Path=/; Max-Age=0; SameSite=Lax");
    return ResponseEntity.ok().headers(headers).body(Map.of("redirectUrl", "/"));
  }

  @PostMapping("/api/v1/organizations")
  public AccessService.Organization createOrganization(
      @Valid @RequestBody OrganizationRequest input) {
    return access.createOrganization(
        identity.self().subjectId(), input.clientRequestId(), input.name());
  }

  @PostMapping("/api/v1/organizations/{id}/transfer-owner")
  public AccessService.Organization transfer(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody TransferRequest input) {
    var scope = identity.scope(request);
    if (!id.equals(scope.organizationId()) || scope.accountId() != null) {
      throw new BusinessException("INVALID_SCOPE", 422, "Выберите область компании");
    }
    return access.transferOwnership(
        scope, input.memberId(), input.expectedRevision(), input.clientRequestId());
  }

  @PutMapping("/api/v1/organizations/{id}")
  public AccessService.Organization rename(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody OrganizationRequest input) {
    var scope = identity.scope(request);
    if (!id.equals(scope.organizationId()) || scope.accountId() != null) {
      throw new BusinessException("INVALID_SCOPE", 422, "Выберите область компании");
    }
    return access.rename(scope, input.name(), input.expectedRevision(), input.clientRequestId());
  }

  @GetMapping("/api/v1/memberships")
  public Page<AccessService.Member> members(
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return access.members(
        identity.scope(request), new AccessQuery(search, status, sort, direction, page, size));
  }

  @PutMapping("/api/v1/memberships/{id}")
  public AccessService.Member updateMember(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody GrantRequest input) {
    return access.updateMembership(
        identity.scope(request),
        id,
        input.expectedRevision(),
        input.permissions(),
        input.clientRequestId());
  }

  @PostMapping("/api/v1/memberships/{id}/revoke")
  public AccessService.Member revokeMember(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return access.revokeMembership(
        identity.scope(request), id, input.expectedRevision(), input.clientRequestId());
  }

  @GetMapping("/api/v1/account-access")
  public Page<AccessService.AccountAccess> accountAccess(
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String sort,
      @RequestParam(defaultValue = "asc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return access.accountAccess(
        identity.scope(request), new AccessQuery(search, status, sort, direction, page, size));
  }

  @PostMapping("/api/v1/memberships/{id}/reactivate")
  public AccessService.Member reactivateMember(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return access.reactivateMembership(
        identity.scope(request), id, input.expectedRevision(), input.clientRequestId());
  }

  @PostMapping("/api/v1/account-access/{id}/revoke")
  public AccessService.AccountAccess revokeAccount(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return access.revokeAccountAccess(
        identity.scope(request), id, input.expectedRevision(), input.clientRequestId());
  }

  @PutMapping("/api/v1/account-access/{id}")
  public AccessService.AccountAccess grant(
      HttpServletRequest request, @PathVariable UUID id, @Valid @RequestBody GrantRequest input) {
    return access.grantAccountAccess(
        identity.scope(request),
        id,
        input.role(),
        input.permissions(),
        input.expectedRevision(),
        input.clientRequestId());
  }

  @PostMapping("/api/v1/invitations")
  public InvitationService.Invitation invite(
      HttpServletRequest request, @Valid @RequestBody InvitationRequest input) {
    return invitations.create(
        identity.scope(request), input.email(), input.role(), Set.of(), input.clientRequestId());
  }

  @GetMapping("/api/v1/invitations")
  public Page<InvitationService.Invitation> invitations(
      HttpServletRequest request,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String sort,
      @RequestParam(defaultValue = "desc") String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return invitations.list(
        identity.scope(request), new AccessQuery(search, status, sort, direction, page, size));
  }

  @PostMapping("/api/v1/invitations/accept")
  public InvitationService.Accepted accept(@Valid @RequestBody AcceptRequest input) {
    return invitations.accept(identity.self().subjectId(), input.token(), input.clientRequestId());
  }

  @PostMapping("/api/v1/invitations/{id}/reissue")
  public InvitationService.Invitation reissue(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return invitations.reissue(
        identity.scope(request), id, input.expectedRevision(), input.clientRequestId());
  }

  @PostMapping("/api/v1/invitations/{id}/revoke")
  public InvitationService.Invitation revokeInvitation(
      HttpServletRequest request,
      @PathVariable UUID id,
      @Valid @RequestBody RevisionRequest input) {
    return invitations.revoke(
        identity.scope(request), id, input.expectedRevision(), input.clientRequestId());
  }

  public record RevisionRequest(@NotNull UUID clientRequestId, long expectedRevision) {}

  public record OrganizationRequest(
      @NotNull UUID clientRequestId,
      long expectedRevision,
      @NotBlank @Size(max = 160) String name) {}

  public record TransferRequest(
      @NotNull UUID clientRequestId, long expectedRevision, @NotNull UUID memberId) {}

  public record GrantRequest(
      @NotNull UUID clientRequestId,
      long expectedRevision,
      AccessService.Role role,
      @NotNull Set<String> permissions) {}

  public record InvitationRequest(
      @NotNull UUID clientRequestId,
      long expectedRevision,
      @NotBlank @Size(max = 254) String email,
      @NotNull AccessService.Role role) {}

  public record AcceptRequest(
      @NotNull UUID clientRequestId, @NotBlank @Size(max = 256) String token) {
    @Override
    public String toString() {
      return "AcceptRequest[redacted]";
    }
  }
}
