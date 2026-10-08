package ru.oritas.repricer.automation.decision;

import java.util.Set;

/** Effective-rule actions use internal economics without delegating financial data to the actor. */
public final class DecisionAuthorization {
  public static final Set<String> INTERNAL_INPUTS = Set.of("finance.read");

  private DecisionAuthorization() {}

  public static boolean effectiveAssignment(DecisionContextAssembler.PreviewRequest request) {
    return request.policyId() == null && !request.draft() && request.fixedTarget() == null;
  }

  public static Set<String> preview(DecisionContextAssembler.PreviewRequest request) {
    return action(effectiveAssignment(request), "decision.preview");
  }

  public static Set<String> action(boolean effectiveAssignment, String action) {
    if (!Set.of("decision.preview", "decision.approve", "command.read").contains(action)) {
      throw new IllegalArgumentException("Unsupported decision action");
    }
    return effectiveAssignment ? Set.of(action) : Set.of(action, "finance.read");
  }
}
