package ru.oritas.repricer.automation.policy;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Resolve specificity before activity so a paused child can never activate its parent. */
public final class PolicyResolver {
  public enum ScopeKind {
    OFFER,
    CATEGORY,
    ACCOUNT
  }

  public enum Status {
    ACTIVE,
    PAUSED,
    DISABLED,
    ARCHIVED
  }

  public record Assignment(
      UUID id,
      UUID policyId,
      ScopeKind kind,
      UUID scopeId,
      int categoryDistance,
      Status status,
      long revision) {}

  public record Resolution(Assignment selected, String reason, boolean active) {}

  public Resolution resolve(List<Assignment> matching) {
    List<Assignment> ordered =
        matching.stream()
            .sorted(
                Comparator.comparingInt((Assignment a) -> a.kind().ordinal())
                    .thenComparingInt(Assignment::categoryDistance))
            .toList();
    if (ordered.isEmpty()) {
      return new Resolution(null, "NO_ASSIGNMENT", false);
    }
    Assignment selected = ordered.getFirst();
    if (ordered.size() > 1
        && ordered.get(1).kind() == selected.kind()
        && ordered.get(1).categoryDistance() == selected.categoryDistance()) {
      return new Resolution(null, "AMBIGUOUS_ASSIGNMENT", false);
    }
    return new Resolution(selected, selected.status().name(), selected.status() == Status.ACTIVE);
  }
}
