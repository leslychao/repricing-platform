package ru.oritas.repricer.app;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import ru.oritas.repricer.platform.JsonCodec;

@Configuration
public class JsonConfiguration implements WebMvcConfigurer {
  private final JsonCodec json;

  public JsonConfiguration(JsonCodec json) {
    this.json = json;
  }

  @Override
  public void configureMessageConverters(HttpMessageConverters.ServerBuilder converters) {
    converters.withJsonConverter(new RequestJsonConverter(json));
  }
}
