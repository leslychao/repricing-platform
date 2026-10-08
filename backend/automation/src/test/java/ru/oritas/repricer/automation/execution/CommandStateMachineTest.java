package ru.oritas.repricer.automation.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.automation.execution.CommandStateMachine.State;
import ru.oritas.repricer.marketplace.CommercialGateway.CommercialEffect;
import ru.oritas.repricer.marketplace.CommercialGateway.EffectEvidence;
import ru.oritas.repricer.marketplace.CommercialGateway.EffectState;
import ru.oritas.repricer.marketplace.CommercialGateway.Reconciliation;

class CommandStateMachineTest {
  private final CommandStateMachine states = new CommandStateMachine();
  private final UUID target = UUID.randomUUID();
  private final List<CommercialEffect> required =
      List.of(
          new CommercialEffect(target, "price", new BigDecimal("100"), null),
          new CommercialEffect(target, "old_price", new BigDecimal("120"), null));

  @Test
  void unresolvedAncillaryFieldRetainsUnknownEvenWhenPriceIsConfirmed() {
    assertEquals(
        State.UNKNOWN,
        states.reconcile(
            State.SENT,
            required,
            new Reconciliation(
                true,
                true,
                List.of(
                    evidence("price", EffectState.CONFIRMED),
                    evidence("old_price", EffectState.UNKNOWN)))));
  }

  @Test
  void partialRequiresEveryEffectKnownAndOriginalScopeComplete() {
    var mixed =
        List.of(
            evidence("price", EffectState.CONFIRMED), evidence("old_price", EffectState.REJECTED));
    assertEquals(
        State.UNKNOWN,
        states.reconcile(State.UNKNOWN, required, new Reconciliation(false, true, mixed)));
    assertEquals(
        State.PARTIALLY_APPLIED,
        states.reconcile(State.UNKNOWN, required, new Reconciliation(true, true, mixed)));
  }

  @Test
  void duplicateEvidenceDoesNotCompleteAnUnknownCommand() {
    var duplicate = evidence("price", EffectState.CONFIRMED);
    assertEquals(
        State.UNKNOWN,
        states.reconcile(
            State.UNKNOWN,
            required,
            new Reconciliation(true, true, List.of(duplicate, duplicate))));
  }

  @Test
  void observedDesiredStateNeverClaimsThatAnUnsentCommandAppliedIt() {
    assertThrows(
        IllegalStateException.class,
        () ->
            states.reconcile(
                State.PENDING,
                required,
                new Reconciliation(
                    true,
                    true,
                    List.of(
                        evidence("price", EffectState.CONFIRMED),
                        evidence("old_price", EffectState.CONFIRMED)))));
  }

  private EffectEvidence evidence(String field, EffectState state) {
    return new EffectEvidence(target, field, state, UUID.randomUUID(), state.name());
  }
}
