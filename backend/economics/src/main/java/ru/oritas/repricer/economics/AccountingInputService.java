package ru.oritas.repricer.economics;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.Scope;

/** Durable input acceptance. The immutable checked set is published atomically by its worker. */
@Service
public class AccountingInputService {
  public record InputSet(
      UUID id, List<EconomicsService.CostInput> costs, EconomicsService.TaxInput tax) {
    public InputSet {
      costs = List.copyOf(costs);
      if (id == null || (costs.isEmpty() && tax == null) || costs.size() > 200) {
        throw new IllegalArgumentException("An input set needs 1..200 bounded rows");
      }
      Set<UUID> distinct =
          costs.stream()
              .map(EconomicsService.CostInput::offerId)
              .collect(java.util.stream.Collectors.toSet());
      if (distinct.size() != costs.size()) {
        throw new IllegalArgumentException("An offer can occur only once in an input set");
      }
    }
  }

  private final AuthorizationService authorization;
  private final JobRuntime jobs;
  private final JsonCodec json;

  public AccountingInputService(
      AuthorizationService authorization, JobRuntime jobs, JsonCodec json) {
    this.authorization = authorization;
    this.jobs = jobs;
    this.json = json;
  }

  @Transactional
  public UUID submit(Scope scope, InputSet input) {
    authorization.require(scope, "finance.write");
    return jobs.submit(scope, "ECONOMICS_INPUT", input.id().toString(), json.encode(input));
  }
}
