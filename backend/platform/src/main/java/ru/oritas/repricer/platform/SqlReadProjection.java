package ru.oritas.repricer.platform;

import java.util.Map;

/** Authorized owner-produced SQL for composing published read models in a single database query. */
public record SqlReadProjection(String sql, Map<String, ?> parameters) {
  public SqlReadProjection {
    parameters = Map.copyOf(parameters);
  }
}
