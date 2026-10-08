package ru.oritas.repricer.automation.decision;

import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JsonCodec;

@Component
public final class DecisionApprovalHandler implements JobHandler {
  private final DecisionService decisions;
  private final JsonCodec json;

  public DecisionApprovalHandler(DecisionService decisions, JsonCodec json) {
    this.decisions = decisions;
    this.json = json;
  }

  @Override
  public String type() {
    return "DECISION_APPROVE";
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    var request = json.decode(context.payload(), DecisionService.ApprovalRequest.class);
    return JobOutcome.succeeded(
        json.encode(
            decisions.approve(
                context.scope(), request.decisionId(), request.expectedRevision(), context)));
  }
}
