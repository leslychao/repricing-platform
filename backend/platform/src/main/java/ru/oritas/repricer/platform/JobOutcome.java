package ru.oritas.repricer.platform;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

public record JobOutcome(
    String state, String result, String reason, Instant dueAt, String nextLane) {
  public JobOutcome {
    if (!Set.of("SUCCEEDED", "WAITING", "BLOCKED", "DEAD", "CANCELLED").contains(state)) {
      throw new IllegalArgumentException("Invalid job outcome");
    }
    if (state.equals("WAITING") && dueAt == null) {
      throw new IllegalArgumentException("Waiting requires a due time");
    }
    if (nextLane != null && (!state.equals("WAITING") || nextLane.isBlank())) {
      throw new IllegalArgumentException("A lane handoff requires a waiting job and a target lane");
    }
  }

  public static JobOutcome succeeded(String result) {
    return new JobOutcome("SUCCEEDED", result, null, null, null);
  }

  public static JobOutcome waiting(String reason, Instant dueAt) {
    return new JobOutcome("WAITING", null, reason, dueAt, null);
  }

  /** Resume the same durable job under the target lane's capacity and a fresh fencing lease. */
  public static JobOutcome handoff(String lane, Instant dueAt) {
    return new JobOutcome(
        "WAITING", null, "JOB_LANE_HANDOFF", dueAt, Objects.requireNonNull(lane));
  }

  public static JobOutcome blocked(String reason) {
    return new JobOutcome("BLOCKED", null, reason, null, null);
  }
}
