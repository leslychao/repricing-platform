package ru.oritas.repricer.economics;

import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.ScopeExecutor;

@Component
public final class AccountingInputHandler implements JobHandler {
  private final EconomicsService economics;
  private final ScopeExecutor scopes;
  private final JobRuntime jobs;
  private final JsonCodec json;
  private final JdbcClient jdbc;

  public AccountingInputHandler(
      EconomicsService economics,
      ScopeExecutor scopes,
      JobRuntime jobs,
      JsonCodec json,
      JdbcClient jdbc) {
    this.economics = economics;
    this.scopes = scopes;
    this.jobs = jobs;
    this.json = json;
    this.jdbc = jdbc;
  }

  @Override
  public String type() {
    return "ECONOMICS_INPUT";
  }

  @Override
  public JobOutcome execute(JobContext context) {
    AccountingInputService.InputSet input =
        json.decode(context.payload(), AccountingInputService.InputSet.class);
    return scopes.execute(
        context.scope(),
        Set.of(),
        () -> {
          jobs.requireOwnership(context);
          var existing =
              jdbc.sql(
                      """
                      SELECT id FROM economics_input_receipt
                      WHERE organization_id=:org AND account_id=:account AND id=:id
                      """)
                  .param("org", context.scope().organizationId())
                  .param("account", context.scope().accountId())
                  .param("id", input.id())
                  .query(java.util.UUID.class)
                  .optional();
          if (existing.isEmpty()) {
            List<EconomicsService.CostInput> ordered =
                input.costs().stream()
                    .sorted(java.util.Comparator.comparing(EconomicsService.CostInput::offerId))
                    .toList();
            for (EconomicsService.CostInput cost : ordered) {
              economics.publishCost(context.scope(), cost);
            }
            if (input.tax() != null) {
              economics.publishTax(context.scope(), input.tax());
            }
            jobs.requireOwnership(context);
            jdbc.sql(
                    """
                    INSERT INTO economics_input_receipt(organization_id,account_id,id,author_id)
                    VALUES (:org,:account,:id,:author)
                    """)
                .param("org", context.scope().organizationId())
                .param("account", context.scope().accountId())
                .param("id", input.id())
                .param("author", context.scope().subjectId())
                .update();
          }
          return JobOutcome.succeeded(json.encode(new Receipt(input.id(), input.costs().size())));
        });
  }

  public record Receipt(java.util.UUID inputSetId, int rows) {}
}
