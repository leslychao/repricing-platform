package ru.oritas.repricer.marketplace;

import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Streams official nested trees; memory depends on depth and one bounded scalar, not node count.
 */
final class CategoryTreeReader {
  record Node(
      int sequence,
      Integer parentSequence,
      String externalId,
      String kind,
      String name,
      boolean disabled) {}

  private final VendorJsonReader.BudgetStream input;
  private final JsonReader json;
  private final boolean ozon;
  private final Consumer<Node> consume;
  private int count;

  private CategoryTreeReader(InputStream source, boolean ozon, Consumer<Node> consume) {
    input = new VendorJsonReader.BudgetStream(source);
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
    this.ozon = ozon;
    this.consume = consume;
  }

  static int parse(InputStream source, boolean ozon, Consumer<Node> consume) throws IOException {
    var reader = new CategoryTreeReader(source, ozon, consume);
    try (reader.json) {
      reader.document();
      return reader.count;
    } catch (IllegalStateException | NumberFormatException error) {
      throw new IOException("Invalid category tree", error);
    }
  }

  private void document() throws IOException {
    boolean found = false;
    String status = null;
    Set<String> names = new HashSet<>();
    json.beginObject();
    while (json.hasNext()) {
      input.resetBudget();
      String field = field(names);
      if (field.equals("result")) {
        found = true;
        if (ozon) {
          json.beginArray();
          while (json.hasNext()) {
            node(null, 0);
          }
          json.endArray();
        } else {
          node(null, 0);
        }
      } else if (field.equals("status")) {
        status = text(32);
      } else {
        json.skipValue();
      }
    }
    json.endObject();
    if (!found
        || count == 0
        || (!ozon && !"OK".equals(status))
        || json.peek() != JsonToken.END_DOCUMENT) {
      throw new IOException("Unconfirmed or incomplete category tree");
    }
  }

  private void node(Integer parent, int depth) throws IOException {
    if (Thread.currentThread().isInterrupted()) {
      throw new InterruptedIOException("Category traversal interrupted");
    }
    if (depth >= 64 || ++count > 1_000_000) {
      throw new IOException("Category tree traversal limit reached");
    }
    int sequence = count;
    long category = -1;
    long type = -1;
    String categoryName = null;
    String typeName = null;
    Boolean disabled = null;
    boolean hasChildren = false;
    int scalarBytes = 0;
    Set<String> names = new HashSet<>();
    json.beginObject();
    while (json.hasNext()) {
      input.resetBudget();
      String field = field(names);
      if (field.equals("children")) {
        scalarBytes += input.recordUpperBytes();
      }
      switch (field) {
        case "description_category_id" -> {if(ozon){category=identifier();}else{json.skipValue();}}
        case "id" -> {if(!ozon){category=identifier();}else{json.skipValue();}}
        case "type_id" -> type = identifier();
        case "category_name" -> {if(ozon){categoryName=text(512);}else{json.skipValue();}}
        case "name" -> {if(!ozon){categoryName=text(512);}else{json.skipValue();}}
        case "type_name" -> typeName = text(512);
        case "disabled" -> disabled = json.nextBoolean();
        case "children" -> {
          if (json.peek() == JsonToken.NULL && !ozon) {
            json.nextNull();
          } else {
            json.beginArray();
            while (json.hasNext()) {
              hasChildren = true;
              node(sequence, depth + 1);
            }
            json.endArray();
          }
        }
        default -> json.skipValue();
      }
      if (!field.equals("children")) {
        scalarBytes += input.recordUpperBytes();
      }
      if (scalarBytes > 1024 * 1024) {
        throw new IOException("Category node exceeds 1 MiB excluding child records");
      }
    }
    json.endObject();
    boolean isType = ozon && type > 0 && category <= 0;
    String name = isType ? typeName : categoryName;
    long identity = isType ? type : category;
    if (identity < 0
        || name == null
        || name.isBlank()
        || (ozon && disabled == null)
        || (isType && (parent == null || hasChildren))) {
      throw new IOException("Category identity or parent relationship is not confirmed");
    }
    consume.accept(
        new Node(
            sequence,
            parent,
            Long.toString(identity),
            isType ? "TYPE" : "CATEGORY",
            name,
            Boolean.TRUE.equals(disabled)));
  }

  private String field(Set<String> names) throws IOException {
    String field = json.nextName();
    if (field.length() > 128 || names.size() >= 32 || !names.add(field)) {
      throw new IOException("Invalid or repeated category field");
    }
    return field;
  }

  private long identifier() throws IOException {
    if (json.peek() != JsonToken.NUMBER) {
      throw new IOException("Category ID must be an integer");
    }
    String value = json.nextString();
    if (!value.matches("0|[1-9][0-9]{0,18}")) {
      throw new IOException("Invalid category ID");
    }
    return Long.parseLong(value);
  }

  private String text(int limit) throws IOException {
    if (json.peek() != JsonToken.STRING) {
      throw new IOException("Category name must be text");
    }
    String value = json.nextString();
    if (value.length() > limit) {
      throw new IOException("Category text exceeds its limit");
    }
    return value;
  }
}
