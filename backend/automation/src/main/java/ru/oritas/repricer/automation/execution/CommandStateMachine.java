package ru.oritas.repricer.automation.execution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import ru.oritas.repricer.marketplace.CommercialGateway.CommercialEffect;
import ru.oritas.repricer.marketplace.CommercialGateway.EffectEvidence;
import ru.oritas.repricer.marketplace.CommercialGateway.EffectState;
import ru.oritas.repricer.marketplace.CommercialGateway.Reconciliation;

public final class CommandStateMachine {
  public enum State {
    PENDING,
    SENT,
    APPLIED,
    PARTIALLY_APPLIED,
    FAILED,
    UNKNOWN,
    CANCELLED
  }

  public boolean terminal(State state) {
    return Set.of(State.APPLIED, State.PARTIALLY_APPLIED, State.FAILED, State.CANCELLED)
        .contains(state);
  }

  public State reconcile(State current, List<CommercialEffect> original, Reconciliation evidence) {
    if (terminal(current)) {
      return current;
    }
    if (current == State.PENDING) {
      throw new IllegalStateException("Unsent state cannot be called applied by us");
    }
    Set<String> required =
        original.stream().map(e -> e.targetId() + ":" + e.field()).collect(Collectors.toSet());
    if (required.size() != original.size()) {
      throw new IllegalArgumentException("Duplicate required effects");
    }
    Map<String, EffectState> actual = new HashMap<>();
    for (EffectEvidence effect : evidence.effects()) {
      String key = effect.targetId() + ":" + effect.field();
      if (!required.contains(key)
          || actual.putIfAbsent(key, effect.state()) != null
          || effect.rawFileId() == null) {
        return State.UNKNOWN;
      }
    }
    if (!evidence.completeOriginalScope()
        || !evidence.processingComplete()
        || actual.size() != required.size()
        || actual.containsValue(EffectState.UNKNOWN)) {
      return State.UNKNOWN;
    }
    if (actual.values().stream().allMatch(s -> s == EffectState.CONFIRMED)) {
      return State.APPLIED;
    }
    return actual.values().stream().allMatch(s -> s == EffectState.REJECTED)
        ? State.FAILED
        : State.PARTIALLY_APPLIED;
  }
}
