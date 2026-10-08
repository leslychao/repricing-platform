package ru.oritas.repricer.platform;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.Strictness;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** Small persisted messages only. Raw responses and files use streaming readers directly. */
@Component
public final class JsonCodec {
  public static final int MAX_MESSAGE_BYTES = 65_536;
  private final Gson gson =
      new GsonBuilder()
          .setStrictness(Strictness.STRICT)
          .registerTypeAdapterFactory(new SetOrderAdapterFactory())
          .registerTypeAdapter(Instant.class, textAdapter(Instant::parse))
          .registerTypeAdapter(LocalDate.class, textAdapter(LocalDate::parse))
          .registerTypeAdapter(LocalTime.class, textAdapter(LocalTime::parse))
          .registerTypeHierarchyAdapter(ZoneId.class, textAdapter(ZoneId::of))
          .registerTypeAdapter(
              BigDecimal.class, textAdapter(JsonCodec::decimal, JsonCodec::decimalText))
          .create();

  public String encode(Object value) {
    var writer = new MessageWriter();
    gson.toJson(value, writer);
    String result = writer.toString();
    checkSize(result);
    return result;
  }

  public Gson gson() {
    return gson;
  }

  /** The caller bounds the record and stream; large collections use an element parser. */
  public void write(Object value, Writer destination) {
    gson.toJson(value, destination);
  }

  public <T> T decode(Reader input, Class<T> type) throws IOException {
    JsonReader reader = new JsonReader(input);
    reader.setNestingLimit(64);
    T result = gson.fromJson(reader, type);
    if (result == null || reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
      throw new JsonParseException("Expected one complete value");
    }
    return result;
  }

  public <T> T decode(String value, Class<T> type) {
    checkSize(value);
    try (JsonReader reader = new JsonReader(new StringReader(value))) {
      reader.setNestingLimit(64);
      T result = gson.fromJson(reader, type);
      if (result == null || reader.peek() != com.google.gson.stream.JsonToken.END_DOCUMENT) {
        throw new JsonParseException("Expected one non-null value");
      }
      return result;
    } catch (IOException | JsonParseException exception) {
      throw new BusinessException("INVALID_MESSAGE", 422, "Некорректная структура данных");
    }
  }

  private static void checkSize(String value) {
    if (value == null
        || value.length() > MAX_MESSAGE_BYTES
        || value.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
      throw new BusinessException("MESSAGE_TOO_LARGE", 422, "Превышен размер сообщения");
    }
  }

  private static <T> TypeAdapter<T> textAdapter(Function<String, T> parser) {
    return textAdapter(parser, Object::toString);
  }

  private static BigDecimal decimal(String text) {
    if (text.length() > 1024) {
      throw new JsonParseException("Decimal exceeds its representation limit");
    }
    BigDecimal result = new BigDecimal(text);
    decimalBound(result);
    return result;
  }

  private static String decimalText(BigDecimal value) {
    decimalBound(value);
    return value.toPlainString();
  }

  private static void decimalBound(BigDecimal value) {
    if (value.precision() + Math.abs((long) value.scale()) > 1024) {
      throw new JsonParseException("Decimal exceeds its representation limit");
    }
  }

  private static <T> TypeAdapter<T> textAdapter(
      Function<String, T> parser, Function<T, String> format) {
    return new TypeAdapter<T>() {
      @Override
      public void write(JsonWriter writer, T value) throws IOException {
        writer.value(format.apply(value));
      }

      @Override
      public T read(JsonReader reader) throws IOException {
        if (reader.peek() != com.google.gson.stream.JsonToken.STRING) {
          throw new JsonParseException("A string value is required");
        }
        return parser.apply(reader.nextString());
      }
    }.nullSafe();
  }

  private static final class MessageWriter extends Writer {
    private final StringBuilder buffer = new StringBuilder();

    @Override
    public void write(char[] characters, int offset, int length) {
      if (length > MAX_MESSAGE_BYTES - buffer.length()) {
        throw new BusinessException("MESSAGE_TOO_LARGE", 422, "Превышен размер сообщения");
      }
      buffer.append(characters, offset, length);
    }

    @Override
    public void flush() {}

    @Override
    public void close() {}

    @Override
    public String toString() {
      return buffer.toString();
    }
  }
}
