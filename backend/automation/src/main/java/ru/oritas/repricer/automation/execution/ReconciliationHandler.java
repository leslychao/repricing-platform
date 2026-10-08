package ru.oritas.repricer.automation.execution;

import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JsonCodec;

@Component
public final class ReconciliationHandler implements JobHandler {
  private final ExecutionHandler execution;
  private final JsonCodec json;

  public ReconciliationHandler(ExecutionHandler execution, JsonCodec json) {
    this.execution = execution;
    this.json = json;
  }

  @Override
  public String type() {
    return "AUTOMATION_RECONCILE";
  }

  @Override
  public String lane() {
    return "execution";
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    var request = json.decode(context.payload(), ExecutionService.ReconcileRequest.class);
    return execution.reconcileOnly(context, request.commandId());
  }
}
