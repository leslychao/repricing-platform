package ru.oritas.repricer.app.realtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfiguration implements WebSocketConfigurer {
  private final RealtimeSocket socket;
  private final String origin;

  public WebSocketConfiguration(RealtimeSocket socket,
      @Value("${repricer.public-base-url}") String origin) {
    this.socket = socket;
    this.origin = origin;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(socket, "/ws").setAllowedOrigins(origin);
  }
}
