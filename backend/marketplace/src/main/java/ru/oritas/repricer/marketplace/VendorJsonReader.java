package ru.oritas.repricer.marketplace;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Parses one bounded record at a time; the remainder of a supplier page is never a tree. */
final class VendorJsonReader {
  enum RecordFormat {
    JSON_ARRAY,
    JSON_LINES
  }

  private static final Set<String> CURSORS = Set.of("last_id", "cursor", "nextPageToken");
  private final BudgetStream input;
  private final JsonReader json;
  private final String recordsPath;
  private final BiConsumer<JsonObject, Integer> consume;
  private String cursor;
  private Long total;
  private Long totalItems;
  private Boolean hasNext;
  private int records;
  private boolean found;
  private Map<String, String> expectedFields = Map.of();
  private final Set<String> verifiedFields = new HashSet<>();

  private VendorJsonReader(
      InputStream source, String recordsPath, BiConsumer<JsonObject, Integer> consume) {
    input = new BudgetStream(source);
    json =
        new JsonReader(
            new InputStreamReader(
                input,
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)));
    json.setStrictness(Strictness.STRICT);
    json.setNestingLimit(64);
    this.recordsPath = recordsPath;
    this.consume = consume;
  }

  static PageInfo parse(InputStream source, String path, Consumer<JsonObject> consumer)
      throws IOException {
    return parseSized(source, path, (value, bytes) -> consumer.accept(value));
  }

  static PageInfo parseSized(
      InputStream source, String path, BiConsumer<JsonObject, Integer> consumer)
      throws IOException {
    return parseSized(source, path, Map.of(), consumer);
  }

  static PageInfo parse(
      InputStream source,
      String path,
      Map<String, String> expectedFields,
      Consumer<JsonObject> consumer)
      throws IOException {
    return parseSized(source, path, expectedFields, (value, bytes) -> consumer.accept(value));
  }

  private static PageInfo parseSized(
      InputStream source,
      String path,
      Map<String, String> expectedFields,
      BiConsumer<JsonObject, Integer> consumer)
      throws IOException {
    VendorJsonReader parser = new VendorJsonReader(source, path, consumer);
    parser.expectedFields = Map.copyOf(expectedFields);
    try (parser.json) {
      parser.object("");
      if (parser.json.peek() != JsonToken.END_DOCUMENT
          || !parser.found
          || !parser.verifiedFields.equals(parser.expectedFields.keySet())) {
        throw new IOException("Expected complete supplier record array");
      }
      return new PageInfo(
          parser.records,
          parser.cursor,
          parser.totalItems == null ? parser.total : parser.totalItems,
          parser.hasNext);
    } catch (JsonParseException exception) {
      if (exception.getCause() instanceof IOException io) {
        throw io;
      }
      throw new IOException("Invalid supplier JSON", exception);
    }
  }

  static JsonObject single(InputStream source, String field) throws IOException {
    VendorJsonReader parser = new VendorJsonReader(source, "", (value, bytes) -> {});
    try (parser.json) {
      var document = JsonParser.parseReader(parser.json);
      if (parser.json.peek() != JsonToken.END_DOCUMENT
          || !document.isJsonObject()
          || !document.getAsJsonObject().has(field)
          || !document.getAsJsonObject().get(field).isJsonObject()) {
        throw new IOException("Expected one complete bounded supplier object");
      }
      return document.getAsJsonObject().getAsJsonObject(field);
    } catch (JsonParseException exception) {
      throw new IOException("Invalid supplier JSON", exception);
    }
  }

  static long reportRows(InputStream source, Consumer<JsonObject> consumer) throws IOException {
    return reportRowsSized(source, (value, bytes) -> consumer.accept(value));
  }

  static long reportRowsSized(InputStream source, BiConsumer<JsonObject, Integer> consumer)
      throws IOException {
    return reportRowsSized(source, RecordFormat.JSON_ARRAY, consumer);
  }

  static long reportRowsSized(
      InputStream source, RecordFormat format, BiConsumer<JsonObject, Integer> consumer)
      throws IOException {
    if (format == RecordFormat.JSON_LINES) {
      return jsonLines(source, consumer);
    }
    VendorJsonReader parser = new VendorJsonReader(source, "", consumer);
    try (parser.json) {
      parser.json.beginArray();
      long rows = 0;
      while (parser.json.hasNext()) {
        parser.input.resetBudget();
        var value = JsonParser.parseReader(parser.json);
        if (!value.isJsonObject() || ++rows > 1_000_000) {
          throw new IOException("Report exceeds its record shape or row limit");
        }
        consumer.accept(value.getAsJsonObject(), parser.input.recordUpperBytes());
      }
      parser.json.endArray();
      if (parser.json.peek() != JsonToken.END_DOCUMENT) {
        throw new IOException("Incomplete report JSON");
      }
      return rows;
    } catch (JsonParseException exception) {
      throw new IOException("Invalid report JSON", exception);
    }
  }

  /** The source profile chooses the format; malformed JSON arrays never fall back to JSONL. */
  private static long jsonLines(InputStream source, BiConsumer<JsonObject, Integer> consumer)
      throws IOException {
    byte[] line = new byte[1024 * 1024];
    int length = 0;
    long rows = 0;
    try (var input = new BufferedInputStream(source, 16384)) {
      int value;
      while ((value = input.read()) != -1) {
        if (value == '\n') {
          if (++rows > 1_000_000) {
            throw new IOException("Report exceeds its row limit");
          }
          consumeLine(line, length, consumer);
          length = 0;
        } else {
          if (length == line.length) {
            throw new IOException("Supplier record exceeds 1 MiB");
          }
          line[length++] = (byte) value;
        }
      }
      if (length > 0) {
        if (++rows > 1_000_000) {
          throw new IOException("Report exceeds its row limit");
        }
        consumeLine(line, length, consumer);
      }
      return rows;
    }
  }

  private static void consumeLine(byte[] line, int length, BiConsumer<JsonObject, Integer> consumer)
      throws IOException {
    try (var reader =
        new JsonReader(
            new InputStreamReader(
                new ByteArrayInputStream(line, 0, length),
                StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)))) {
      reader.setStrictness(Strictness.STRICT);
      reader.setNestingLimit(64);
      var value = JsonParser.parseReader(reader);
      if (!value.isJsonObject() || reader.peek() != JsonToken.END_DOCUMENT) {
        throw new IOException("Expected one complete JSON object on each line");
      }
      consumer.accept(value.getAsJsonObject(), length);
    } catch (JsonParseException exception) {
      throw new IOException("Invalid supplier JSONL", exception);
    }
  }

  private void object(String path) throws IOException {
    json.beginObject();
    while (json.hasNext()) {
      input.resetBudget();
      String name = json.nextName();
      if (name.length() > 256) {
        throw new IOException("Supplier field name exceeds its limit");
      }
      String current = path.isEmpty() ? name : path + "." + name;
      JsonToken token = json.peek();
      if (expectedFields.containsKey(current)) {
        if ((token != JsonToken.STRING && token != JsonToken.NUMBER)
            || !verifiedFields.add(current)
            || !expectedFields.get(current).equals(json.nextString())) {
          throw new IOException("Supplier response scope does not match request");
        }
      } else if (current.equals(recordsPath)) {
        if (found) {
          throw new IOException("Duplicate supplier record array");
        }
        found = true;
        json.beginArray();
        while (json.hasNext()) {
          input.resetBudget();
          var value = JsonParser.parseReader(json);
          if (!value.isJsonObject() || ++records > 1000) {
            throw new IOException("Supplier record page exceeds its verified limit");
          }
          consume.accept(value.getAsJsonObject(), input.recordUpperBytes());
        }
        json.endArray();
      } else if (token == JsonToken.BEGIN_OBJECT) {
        object(current);
      } else if (CURSORS.contains(name) && token == JsonToken.STRING) {
        String next = json.nextString();
        if (next.length() > 4096) {
          throw new IOException("Supplier cursor exceeds its limit");
        }
        cursor = next;
      } else if (Set.of("total", "totalCount", "total_items").contains(name)
          && (token == JsonToken.NUMBER || token == JsonToken.STRING)) {
        long value = json.nextLong();
        if (value < 0) {
          throw new IOException("Invalid supplier total");
        }
        if (name.equals("total_items")) {
          totalItems = value;
        } else {
          total = value;
        }
      } else if (name.equals("has_next")) {
        if (token != JsonToken.BOOLEAN || hasNext != null) {
          throw new IOException("Invalid supplier continuation flag");
        }
        hasNext = json.nextBoolean();
      } else {
        json.skipValue();
      }
    }
    json.endObject();
  }

  record PageInfo(int records, String cursor, Long total, Boolean hasNext) {}

  static final class BudgetStream extends FilterInputStream {
    // Leave room for the UTF-8 decoder and JsonReader look-ahead already read at a boundary.
    private static final long MAX_READ_BYTES = 1024 * 1024 - 16384;
    private long budget = MAX_READ_BYTES;

    BudgetStream(InputStream input) {
      super(input);
    }

    void resetBudget() {
      budget = MAX_READ_BYTES;
    }

    int recordUpperBytes() {
      return Math.toIntExact(MAX_READ_BYTES - budget + 16384);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      if (budget <= 0) {
        throw new IOException("Supplier record exceeds 1 MiB");
      }
      int read = in.read(buffer, offset, (int) Math.min(length, budget));
      if (read > 0) {
        budget -= read;
      }
      return read;
    }

    @Override
    public int read() throws IOException {
      if (budget-- <= 0) {
        throw new IOException("Supplier record exceeds 1 MiB");
      }
      return in.read();
    }
  }
}
