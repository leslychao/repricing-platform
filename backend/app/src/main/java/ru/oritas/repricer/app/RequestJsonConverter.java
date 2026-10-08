package ru.oritas.repricer.app;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.Reader;
import java.io.StringReader;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import org.springframework.http.converter.json.GsonHttpMessageConverter;
import ru.oritas.repricer.platform.JsonCodec;

/** Bounded JSON commands with exact record fields. Larger source files have a separate stream API. */
final class RequestJsonConverter extends GsonHttpMessageConverter {
  RequestJsonConverter(JsonCodec json) {
    super(json.gson());
  }

  @Override
  protected Object readInternal(Type resolvedType, Reader source) throws Exception {
    StringBuilder text = new StringBuilder();
    char[] buffer = new char[8192];
    int count;
    while ((count = source.read(buffer)) != -1) {
      if (text.length() + count > JsonCodec.MAX_MESSAGE_BYTES) {
        throw new JsonParseException("Command exceeds its limit");
      }
      text.append(buffer, 0, count);
    }
    if (text.toString().getBytes(StandardCharsets.UTF_8).length > JsonCodec.MAX_MESSAGE_BYTES) {
      throw new JsonParseException("Command exceeds its limit");
    }
    try (JsonReader reader = new JsonReader(new StringReader(text.toString()))) {
      reader.setNestingLimit(64);
      reader.setStrictness(com.google.gson.Strictness.STRICT);
      JsonElement input = value(reader);
      if (reader.peek() != JsonToken.END_DOCUMENT) {
        throw new JsonParseException("Expected one command");
      }
      fields(input, resolvedType);
      return getGson().fromJson(input, resolvedType);
    }
  }

  private static JsonElement value(JsonReader reader) throws java.io.IOException {
    return switch (reader.peek()) {
      case BEGIN_OBJECT -> {
        JsonObject object = new JsonObject();
        reader.beginObject();
        while (reader.hasNext()) {
          String key = reader.nextName();
          if (object.has(key)) {
            throw new JsonParseException("Duplicate field");
          }
          object.add(key, value(reader));
        }
        reader.endObject();
        yield object;
      }
      case BEGIN_ARRAY -> {
        JsonArray array = new JsonArray();
        reader.beginArray();
        while (reader.hasNext()) {
          array.add(value(reader));
        }
        reader.endArray();
        yield array;
      }
      case STRING -> new JsonPrimitive(reader.nextString());
      case NUMBER -> {
        String number = reader.nextString();
        if (number.length() > 80) {
          throw new JsonParseException("Number exceeds its limit");
        }
        var decimal = new java.math.BigDecimal(number);
        if (decimal.precision() + Math.abs((long) decimal.scale()) > 80) {
          throw new JsonParseException("Number precision exceeds its limit");
        }
        yield new JsonPrimitive(decimal);
      }
      case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
      case NULL -> {
        reader.nextNull();
        yield JsonNull.INSTANCE;
      }
      default -> throw new JsonParseException("Unexpected JSON token");
    };
  }

  private static void fields(JsonElement input, Type type) {
    if (input.isJsonNull()) {
      if (type instanceof Class<?> raw && raw.isPrimitive()) {
        throw new JsonParseException("A primitive command field cannot be null");
      }
      return;
    }
    if (type instanceof ParameterizedType parameterized
        && parameterized.getRawType() instanceof Class<?> raw) {
      Type[] arguments = parameterized.getActualTypeArguments();
      if (Collection.class.isAssignableFrom(raw)) {
        for (JsonElement element : input.getAsJsonArray()) {
          fields(element, arguments[0]);
        }
      } else if (Map.class.isAssignableFrom(raw)) {
        for (JsonElement element : input.getAsJsonObject().asMap().values()) {
          fields(element, arguments[1]);
        }
      }
      return;
    }
    if (type instanceof Class<?> raw && raw.isRecord()) {
      var components = Arrays.stream(raw.getRecordComponents()).collect(java.util.stream.Collectors.toMap(
          java.lang.reflect.RecordComponent::getName, java.lang.reflect.RecordComponent::getGenericType));
      for (var component : raw.getRecordComponents()) {
        if (component.getType().isPrimitive() && !input.getAsJsonObject().has(component.getName())) {
          throw new JsonParseException("A required command field is missing");
        }
      }
      for (var entry : input.getAsJsonObject().entrySet()) {
        Type member = components.get(entry.getKey());
        if (member == null) {
          throw new JsonParseException("Unknown command field");
        }
        fields(entry.getValue(), member);
      }
    } else if (type instanceof Class<?> raw) {
      JsonPrimitive primitive = input.getAsJsonPrimitive();
      if (raw == boolean.class || raw == Boolean.class) {
        if (!primitive.isBoolean()) {
          throw new JsonParseException("A boolean is required");
        }
      } else if (raw == long.class || raw == Long.class || raw == int.class || raw == Integer.class) {
        if (!primitive.isNumber()) {
          throw new JsonParseException("An integer is required");
        }
        if (raw == int.class || raw == Integer.class) {
          primitive.getAsBigDecimal().intValueExact();
        } else {
          primitive.getAsBigDecimal().longValueExact();
        }
      } else if (!primitive.isString()) {
        throw new JsonParseException("A string is required");
      } else if (raw.isEnum() && Arrays.stream(raw.getEnumConstants())
          .noneMatch(value -> ((Enum<?>) value).name().equals(primitive.getAsString()))) {
        throw new JsonParseException("Unknown enum value");
      }
    }
  }
}
