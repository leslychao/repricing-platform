package ru.oritas.repricer.marketplace;

import ru.oritas.repricer.platform.JobContext;
import ru.oritas.repricer.platform.JobHandler;
import ru.oritas.repricer.platform.JobOutcome;

/** Drives one source page across its durable lane boundaries in owner integration fixtures. */
final class SourceJobSteps {
  private SourceJobSteps() {}

  static JobOutcome executeStep(JobHandler handler, JobContext initial) throws Exception {
    JobContext context = initial;
    for (int transition = 0; transition < 3; transition++) {
      JobOutcome outcome = handler.execute(context);
      if (outcome.nextLane() == null || outcome.nextLane().equals("fetch")) {
        return outcome;
      }
      context = inLane(context, outcome.nextLane());
    }
    throw new AssertionError("A source page did not complete within three lane transitions");
  }

  static JobContext inLane(JobContext context, String lane) {
    return new JobContext(
        context.id(),
        context.scope(),
        context.payload(),
        context.fence() + 1,
        context.deadline(),
        context.attempt(),
        lane);
  }
}
