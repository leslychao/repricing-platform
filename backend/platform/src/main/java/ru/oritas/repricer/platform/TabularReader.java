package ru.oritas.repricer.platform;

import com.univocity.parsers.common.TextParsingException;
import com.univocity.parsers.csv.CsvParser;
import com.univocity.parsers.csv.CsvParserSettings;
import com.univocity.parsers.csv.UnescapedQuoteHandling;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A bounded row at a time; consumers persist checkpoints before requesting the next row. */
public final class TabularReader {
  public static final int MAX_CELL_BYTES = 32768;
  public static final int MAX_ROWS = 100001;
  public static final int MAX_COLUMNS = 50;

  public void csv(InputStream source, char delimiter, RowConsumer consumer) throws IOException {
    if (delimiter != ';' && delimiter != ',') {
      throw new IllegalArgumentException("Only explicit CSV delimiters are supported");
    }
    CsvParserSettings settings = new CsvParserSettings();
    settings.getFormat().setDelimiter(delimiter);
    settings.setDelimiterDetectionEnabled(false);
    settings.setQuoteDetectionEnabled(false);
    settings.setLineSeparatorDetectionEnabled(true);
    settings.setHeaderExtractionEnabled(false);
    settings.setReadInputOnSeparateThread(false);
    settings.setAutoClosingEnabled(false);
    settings.setIgnoreLeadingWhitespaces(false);
    settings.setIgnoreTrailingWhitespaces(false);
    settings.setSkipEmptyLines(false);
    settings.setMaxCharsPerColumn(MAX_CELL_BYTES);
    settings.setMaxColumns(MAX_COLUMNS);
    settings.setInputBufferSize(65536);
    settings.setErrorContentLength(0);
    settings.setUnescapedQuoteHandling(UnescapedQuoteHandling.RAISE_ERROR);
    CsvParser parser = new CsvParser(settings);
    var decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    PushbackReader reader = new PushbackReader(new InputStreamReader(source, decoder), 1);
    try {
      int first = reader.read();
      if (first != -1 && first != 0xfeff) {
        reader.unread(first);
      }
      parser.beginParsing(reader);
      String[] values;
      int row = 0;
      while ((values = parser.parseNext()) != null) {
        if (++row > MAX_ROWS) {
          throw new TableFormatException("Import row limit exceeded");
        }
        List<Cell> cells = new ArrayList<>(values.length);
        for (String value : values) {
          String text = value == null ? "" : value;
          validateCell(text);
          cells.add(new Cell(text, false));
        }
        consumer.accept(new Row(row, cells));
      }
      if (row < 2) {
        throw new TableFormatException("Import has no data rows");
      }
    } catch (TextParsingException exception) {
      Throwable cause = exception.getCause();
      for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
        if (cause instanceof java.nio.charset.CharacterCodingException) {
          throw new TableFormatException("CSV is not valid UTF-8");
        }
        if (cause instanceof IOException failure) {
          throw new IOException("CSV source could not be read completely", failure);
        }
      }
      throw new TableFormatException(
          "CSV has an invalid format or exceeds the cell or column limit");
    } catch (java.nio.charset.CharacterCodingException exception) {
      throw new TableFormatException("CSV is not valid UTF-8");
    } finally {
      parser.stopParsing();
    }
  }

  public static void validateCell(String text) throws IOException {
    if (text.length() > MAX_CELL_BYTES
        || text.getBytes(StandardCharsets.UTF_8).length > MAX_CELL_BYTES) {
      throw new TableFormatException("Cell exceeds 32 KiB");
    }
  }

  @FunctionalInterface
  public interface RowConsumer {
    void accept(Row row) throws IOException;
  }

  public record Cell(String text, boolean numeric) {}

  public record Row(int number, List<Cell> cells) {
    public Row {
      cells = List.copyOf(cells);
    }
  }
}
