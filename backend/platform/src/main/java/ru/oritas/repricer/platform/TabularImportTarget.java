package ru.oritas.repricer.platform;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** A business owner stages an entire input set before publishing any of its rows. */
public interface TabularImportTarget {
  String kind();

  Set<String> permissions();

  List<Column> columns();

  void begin(Scope scope, UUID importId);

  void stage(Scope scope, UUID importId, List<InputRow> rows);

  void seal(Scope scope, UUID importId);

  /** A bounded preparation transaction; true means the whole set is ready for publication. */
  boolean prepareNext(Scope scope, UUID importId);

  /** Acquire the business input scope before locking the import set for publish or cancel. */
  void lockForPublication(Scope scope, UUID importId);

  /** Atomically publishes the prepared set with current authorization and revision checks. */
  void commit(Scope scope, UUID importId);

  enum Type { TEXT, DECIMAL, DATE, INSTANT, BOOLEAN }

  record Column(String name, Type type, boolean required) {}

  record InputRow(int number, Map<String, String> values) {
    public InputRow {
      values = Map.copyOf(values);
    }

    public String value(String column) {
      return values.get(column);
    }
  }
}
