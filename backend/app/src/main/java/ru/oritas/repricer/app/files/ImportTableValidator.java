package ru.oritas.repricer.app.files;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import ru.oritas.repricer.platform.TabularImportTarget.Column;
import ru.oritas.repricer.platform.TabularImportTarget.InputRow;
import ru.oritas.repricer.platform.TabularImportTarget.Type;
import ru.oritas.repricer.platform.TabularReader.Cell;
import ru.oritas.repricer.platform.TabularReader.Row;
import ru.oritas.repricer.platform.TableFormatException;

/** The fixed template validates syntax; its business owner validates and publishes the values. */
final class ImportTableValidator {
  private final List<Column> template;
  private final boolean decimalComma;
  private List<Column> positions;

  ImportTableValidator(List<Column> template, boolean decimalComma) {
    this.template = List.copyOf(template);
    this.decimalComma = decimalComma;
  }

  Checked accept(Row row) throws IOException {
    if (positions == null) {
      Map<String, Column> allowed = new HashMap<>();
      for (Column column : template) {
        allowed.put(column.name(), column);
      }
      List<Column> headers = new ArrayList<>();
      var unique = new HashSet<String>();
      for (Cell cell : row.cells()) {
        if (cell.numeric() || !unique.add(cell.text()) || !allowed.containsKey(cell.text())) {
          throw new TableFormatException("Headers must be unique names from the selected fixed template");
        }
        headers.add(allowed.get(cell.text()));
      }
      if (headers.size() != template.size()) {
        throw new TableFormatException("Headers do not match the selected fixed template");
      }
      positions = List.copyOf(headers);
      return null;
    }
    var errors = new ArrayList<String>();
    Map<String, String> values = new HashMap<>();
    if (row.cells().size() > positions.size()) {
      errors.add("EXTRA_COLUMNS");
    }
    for (int index = 0; index < positions.size(); index++) {
      Column column = positions.get(index);
      Cell cell = index < row.cells().size() ? row.cells().get(index) : new Cell("", false);
      try {
        if (cell.text().isEmpty()) {
          if (column.required()) {
            throw new IllegalArgumentException("REQUIRED");
          }
          continue;
        }
        values.put(column.name(), normalize(column.type(), cell));
      } catch (IllegalArgumentException | DateTimeParseException failure) {
        if (errors.size() < 5) {
          errors.add(column.name() + ":INVALID_" + column.type().name());
        }
      }
    }
    return new Checked(new InputRow(row.number(), values), List.copyOf(errors));
  }

  private String normalize(Type type, Cell cell) {
    String value = cell.text();
    if (type != Type.DECIMAL && cell.numeric()) {
      throw new IllegalArgumentException("Text required");
    }
    return switch (type) {
      case TEXT -> value;
      case BOOLEAN -> {
        if (!value.equals("true") && !value.equals("false")) {
          throw new IllegalArgumentException("Explicit boolean required");
        }
        yield value;
      }
      case DATE -> {
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) {
          throw new IllegalArgumentException("ISO date required");
        }
        yield LocalDate.parse(value).toString();
      }
      case INSTANT -> OffsetDateTime.parse(value).toInstant().toString();
      case DECIMAL -> {
        if (value.length() > 80) {
          throw new IllegalArgumentException("Decimal length exceeded");
        }
        if (!cell.numeric() && !value.matches(decimalComma ? "-?[0-9]+(,[0-9]+)?"
            : "-?[0-9]+(\\.[0-9]+)?")) {
          throw new IllegalArgumentException("Explicit decimal separator required");
        }
        BigDecimal decimal = new BigDecimal(!cell.numeric() && decimalComma
            ? value.replace(',', '.') : value);
        if (decimal.precision() + Math.abs((long) decimal.scale()) > 78) {
          throw new IllegalArgumentException("Decimal precision exceeded");
        }
        yield decimal.toPlainString();
      }
    };
  }

  record Checked(InputRow row, List<String> errors) {}
}
