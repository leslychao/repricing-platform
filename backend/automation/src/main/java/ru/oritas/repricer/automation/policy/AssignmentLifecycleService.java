package ru.oritas.repricer.automation.policy;

import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.oritas.repricer.automation.runtime.ScenarioService;
import ru.oritas.repricer.platform.Scope;

/** Removes a binding only after its accepted temporary episodes have finished. */
@Service
public class AssignmentLifecycleService {
  private final PolicyService policies;
  private final ScenarioService scenarios;

  public AssignmentLifecycleService(PolicyService policies, ScenarioService scenarios) {
    this.policies = policies;
    this.scenarios = scenarios;
  }

  @Transactional
  public PolicyService.DetachReview review(Scope scope, UUID id, long revision) {
    var review = policies.reviewDetach(scope, id, revision);
    scenarios.requireNoActiveRun(scope, id);
    return review;
  }

  @Transactional
  public PolicyService.Assignment detach(Scope scope, UUID id, long revision, String digest) {
    scenarios.requireNoActiveRun(scope, id);
    return policies.detach(scope, id, revision, digest);
  }
}
