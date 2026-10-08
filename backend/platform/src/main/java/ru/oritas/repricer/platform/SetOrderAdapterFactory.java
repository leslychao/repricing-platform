package ru.oritas.repricer.platform;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;

/** Set order has no business meaning and must not change a durable request hash after restart. */
final class SetOrderAdapterFactory implements TypeAdapterFactory {
  @Override
  public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
    if (!Set.class.isAssignableFrom(type.getRawType())) {
      return null;
    }
    TypeAdapter<T> delegate = gson.getDelegateAdapter(this, type);
    return new TypeAdapter<T>() {
      @Override
      public void write(JsonWriter writer, T value) throws IOException {
        JsonElement tree = delegate.toJsonTree(value);
        var elements = new ArrayList<String>();
        for (JsonElement element : tree.getAsJsonArray()) {
          elements.add(element.toString());
        }
        elements.sort(String::compareTo);
        writer.beginArray();
        for (String element : elements) {
          writer.jsonValue(element);
        }
        writer.endArray();
      }

      @Override
      public T read(JsonReader reader) throws IOException {
        return delegate.read(reader);
      }
    }.nullSafe();
  }
}
