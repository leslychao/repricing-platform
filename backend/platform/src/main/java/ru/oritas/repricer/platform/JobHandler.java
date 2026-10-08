package ru.oritas.repricer.platform;

import java.util.Set;
import java.time.Duration;

public interface JobHandler {
  String type();

  default String lane() {
    return "service";
  }

  /** Only handlers, never HTTP input, may declare narrowly scoped service authority. */
  default Set<String> servicePermissions() {
    return Set.of();
  }

  default Duration maximumAttemptDuration() {
    return Duration.ofSeconds(120);
  }

  JobOutcome execute(JobContext context) throws Exception;
}
