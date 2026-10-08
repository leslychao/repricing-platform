package ru.oritas.repricer.platform;

import java.util.Set;

/** Releases only evidence whose business lifecycle has conclusively ended. */
public interface FileRetentionOwner {
  String ownerType();

  Set<String> permissions();

  /** Runs inside one scoped transaction and examines at most {@code limit} business objects. */
  int releaseEligible(Scope scope, int limit);
}
