package ru.oritas.repricer.platform;

import java.util.Set;
import java.util.function.Supplier;

/** Implemented by access; platform cannot manufacture an authorization context. */
public interface ScopeExecutor {
  <T> T execute(Scope scope, Set<String> servicePermissions, Supplier<T> operation);
}
