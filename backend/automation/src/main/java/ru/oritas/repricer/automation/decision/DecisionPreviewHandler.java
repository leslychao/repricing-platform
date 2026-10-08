package ru.oritas.repricer.automation.decision;

import org.springframework.stereotype.Component;
import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.ScopeExecutor;

@Component
public final class DecisionPreviewHandler implements JobHandler {
  private final DecisionContextAssembler assembler;
  private final DecisionService decisions;
  private final ScopeExecutor scopes;
  private final JobRuntime jobs;
  private final JsonCodec json;

  public DecisionPreviewHandler(
      DecisionContextAssembler assembler,
      DecisionService decisions,
      ScopeExecutor scopes,
      JobRuntime jobs,
      JsonCodec json) {
    this.assembler = assembler;
    this.decisions = decisions;
    this.scopes = scopes;
    this.jobs = jobs;
    this.json = json;
  }

  @Override
  public String type() {
    return "DECISION_PREVIEW";
  }

  @Override
  public String lane() {
    return "calculation";
  }

  @Override
  public JobOutcome execute(JobContext context) throws Exception {
    var request = json.decode(context.payload(), DecisionContextAssembler.PreviewRequest.class);
    var assembly =
        scopes.execute(
            context.scope(),
            DecisionAuthorization.INTERNAL_INPUTS,
            () -> {
              jobs.requireOwnership(context);
              return assembler.assemble(context.scope(), request);
            });
    var result = decisions.calculate(context.scope(), context.id(), assembly, context);
    return JobOutcome.succeeded(json.encode(result));
  }
}
