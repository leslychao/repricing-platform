package ru.oritas.repricer.automation.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.automation.policy.PolicyResolver.Assignment;
import ru.oritas.repricer.automation.policy.PolicyResolver.ScopeKind;
import ru.oritas.repricer.automation.policy.PolicyResolver.Status;

class PolicyResolverTest {
  @Test
  void pausedOfferAssignmentBlocksActiveCategoryAndAccountParents() {
    UUID child = UUID.randomUUID();
    var result =
        new PolicyResolver()
            .resolve(
                List.of(
                    new Assignment(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        ScopeKind.ACCOUNT,
                        UUID.randomUUID(),
                        0,
                        Status.ACTIVE,
                        1),
                    new Assignment(
                        child,
                        UUID.randomUUID(),
                        ScopeKind.OFFER,
                        UUID.randomUUID(),
                        0,
                        Status.PAUSED,
                        1)));
    assertEquals(child, result.selected().id());
    assertFalse(result.active());
  }
}
