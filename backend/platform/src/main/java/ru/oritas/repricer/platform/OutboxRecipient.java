package ru.oritas.repricer.platform;

import java.util.Set;
import java.util.UUID;

/** Database effects and delivery receipt share one transaction. */
public interface OutboxRecipient {
  String name();

  boolean accepts(String eventType);

  default Set<String> servicePermissions() {
    return Set.of();
  }

  void receive(Scope scope, UUID eventId, String eventType, String payload);
}
