package ru.oritas.repricer.marketplace;

import java.io.InputStream;
import java.time.Clock;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;

@Component
public final class ConnectionCheckHandler implements JobHandler {
  private final ScopeTransactionRunner transactions;
  private final AuthorizationService authorization;
  private final ConnectionService connections;
  private final OutboundGateway gateway;
  private final CapabilityService capabilities;
  private final JobRuntime jobs;
  private final Clock clock;
  private final JsonCodec json;

  public ConnectionCheckHandler(
      ScopeTransactionRunner transactions,
      AuthorizationService authorization,
      ConnectionService connections,
      OutboundGateway gateway,
      CapabilityService capabilities,
      JobRuntime jobs,
      Clock clock,
      JsonCodec json) {
    this.transactions = transactions;
    this.authorization = authorization;
    this.connections = connections;
    this.gateway = gateway;
    this.capabilities = capabilities;
    this.jobs = jobs;
    this.clock = clock;
    this.json = json;
  }

  @Override
  public String type() {
    return "CONNECTION_CHECK";
  }

  @Override
  public String lane() {
    return "fetch";
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    var check = json.decode(context.payload(), ConnectionService.CheckRequest.class);
    ConnectionService.Credentials connection =
        transactions.run(
            context.scope(),
            () -> {
              jobs.requireOwnership(context);
              authorization.require(context.scope(), "connection.manage");
              ConnectionService.Credentials current =
                  check.candidateId() == null
                      ? connections.current(context.scope())
                      : connections.candidate(context.scope(), check.candidateId());
              capabilities.initializeUnconfirmed(context.scope(), current.marketplace());
              return current;
            });
    boolean ozon = connection.marketplace().equals("OZON");
    VendorMethod method = ozon ? VendorMethod.OZON_CATALOG : VendorMethod.YANDEX_CATALOG;
    String body = ozon ? "{\"filter\":{\"visibility\":\"ALL\"},\"limit\":1}" : "{}";
    var response =
        check.candidateId() == null
            ? gateway.execute(context.scope(), method, body, null, null, 1, null)
            : gateway.checkCandidate(context.scope(), check.candidateId(), method, body);
    if (response.retryAt() != null) {
      return JobOutcome.waiting("QUOTA_WAIT", response.retryAt());
    }
    if (response.status() == 420 || response.status() == 429) {
      return JobOutcome.waiting("VENDOR_RATE_LIMIT", clock.instant().plusSeconds(60));
    }
    if (response.status() >= 500) {
      return JobOutcome.waiting("SUPPLIER_TEMPORARY", clock.instant().plusSeconds(60));
    }
    boolean valid = response.successful();
    if (valid) {
      try (InputStream input = gateway.open(response)) {
        VendorJsonReader.parse(
            input,
            ozon ? "result.items" : "result.offerMappings",
            item -> {
              if (item.isEmpty()) {
                throw new IllegalArgumentException("Empty supplier record");
              }
            });
      }
    }
    OutboundGateway.Response authorizationResponse = null;
    Set<String> apiScopes = Set.of();
    if (valid && !ozon) {
      authorizationResponse =
          check.candidateId() == null
              ? gateway.execute(
                  context.scope(), VendorMethod.YANDEX_AUTH_TOKEN, "{}", null, null, 1, null)
              : gateway.checkCandidate(
                  context.scope(), check.candidateId(), VendorMethod.YANDEX_AUTH_TOKEN, "{}");
      if (authorizationResponse.retryAt() != null) {
        return JobOutcome.waiting("QUOTA_WAIT", authorizationResponse.retryAt());
      }
      if (authorizationResponse.status() >= 500
          || Set.of(420, 429).contains(authorizationResponse.status())) {
        return JobOutcome.waiting("SUPPLIER_TEMPORARY", clock.instant().plusSeconds(60));
      }
      if (authorizationResponse.successful()) {
        try (InputStream input = gateway.open(authorizationResponse)) {
          apiScopes = CapabilityService.yandexScopes(input);
        }
      }
    }
    var scopeResponse = authorizationResponse;
    Set<String> observedScopes = apiScopes;
    transactions.run(
        context.scope(),
        () -> {
          jobs.requireOwnership(context);
          if (check.candidateId() == null) {
            capabilities.requireCredential(context.scope(), response.credentialRevision());
          }
          boolean sameCapabilities =
              !ozon
                  && scopeResponse != null
                  && scopeResponse.successful()
                  && capabilities.equivalentYandexScopes(
                      context.scope(), observedScopes, connection.readOnly());
          if (check.candidateId() == null) {
            connections.checked(context.scope(), response.credentialRevision(), valid);
          } else {
            connections.finishCandidate(
                context.scope(), check.candidateId(), valid, sameCapabilities);
          }
          if (!valid && check.candidateId() == null) {
            capabilities.invalidateConfirmed(context.scope(), "CREDENTIAL_REJECTED");
          } else if (valid && !sameCapabilities && (!ozon || check.candidateId() != null)) {
            capabilities.invalidateConfirmed(
                context.scope(), "CREDENTIAL_SCOPE_REQUIRES_VALIDATION");
          }
          if (valid) {
            capabilities.confirmRead(context.scope(), method, response.raw().id());
            if (scopeResponse != null && scopeResponse.successful()) {
              capabilities.confirmYandexScopes(
                  context.scope(), scopeResponse.raw().id(), observedScopes);
            }
          }
        });
    return valid
        ? JobOutcome.succeeded("{\"connected\":true}")
        : JobOutcome.blocked("CREDENTIAL_OR_SCOPE_REJECTED");
  }
}
