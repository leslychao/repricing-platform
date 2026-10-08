package ru.oritas.repricer.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.platform.JobControlConnections;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "repricer.mode", havingValue = "worker")
class JobControlConfiguration {
  @Bean
  JobControlConnections jobControlConnections(
      @Value("${spring.datasource.url}") String url,
      @Value("${spring.datasource.username}") String username,
      @Value("${spring.datasource.password}") String password) {
    return new JobControlConnections(url, username, password);
  }
}
