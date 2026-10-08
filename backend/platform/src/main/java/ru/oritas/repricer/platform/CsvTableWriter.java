package ru.oritas.repricer.platform;

import java.io.IOException;
import java.io.Writer;
import java.util.List;

/** CSV preserves exact decimal text and neutralizes spreadsheet expressions in text cells. */
public final class CsvTableWriter {
  private final Writer output;
  private final int columns;
  private long rows;

  public CsvTableWriter(Writer output, List<String> headers) throws IOException {
    if (headers.isEmpty() || headers.size() > 100) {
      throw new IllegalArgumentException("Invalid export column count");
    }
    this.output = output;
    this.columns = headers.size();
    row(headers.stream().map(Cell::text).toList());
    rows = 0;
  }

  public void row(List<Cell> cells) throws IOException {
    if (++rows > 1_000_000 || cells.size() != columns) {
      throw new BusinessException(
          "EXPORT_TABLE_LIMIT", 422, "Превышен предел строк или колонок выгрузки");
    }
    for (int index = 0; index < cells.size(); index++) {
      if (index > 0) {
        output.write(';');
      }
      Cell cell = cells.get(index);
      String value = cell.value() == null ? "" : cell.value();
      try {
        TabularReader.validateCell(value);
      } catch (TableFormatException exception) {
        throw new BusinessException("EXPORT_CELL_LIMIT", 422, "Ячейка превышает допустимый размер");
      }
      if (!cell.numeric() && dangerous(value)) {
        value = "'" + value;
      }
      output.write('"');
      for (int character = 0; character < value.length(); character++) {
        char current = value.charAt(character);
        if (current == '"') {
          output.write('"');
        }
        output.write(current);
      }
      output.write('"');
    }
    output.write("\r\n");
  }

  static boolean dangerous(String value) {
    String trimmed = value.stripLeading();
    return !trimmed.isEmpty() && "=+-@\t\r\n".indexOf(trimmed.charAt(0)) >= 0
        || !value.isEmpty() && "\t\r\n".indexOf(value.charAt(0)) >= 0;
  }

  public record Cell(String value, boolean numeric) {
    public static Cell text(String value) {
      return new Cell(value, false);
    }

    public static Cell number(java.math.BigDecimal value) {
      return new Cell(value == null ? "" : value.toPlainString(), true);
    }
  }
}
