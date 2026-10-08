package ru.oritas.repricer.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Removes only completed technical metadata; incoming durable foreign keys retain business work.
 */
@Service
public final class PlatformMetadataRetention {
  private static final Logger log = LoggerFactory.getLogger(PlatformMetadataRetention.class);
  private final JdbcClient jdbc;
  private final boolean worker;

  public PlatformMetadataRetention(JdbcClient jdbc, @Value("${repricer.mode:api}") String mode) {
    this.jdbc = jdbc;
    this.worker = mode.equals("worker");
  }

  @Scheduled(fixedDelay = 3600000)
  public void clean() {
    if (!worker) {
      return;
    }
    try {
      jdbc.sql("SELECT repricer_cleanup_platform_metadata()").query(Integer.class).single();
    } catch (RuntimeException failure) {
      log.warn("Metadata retention failed: {}", failure.getClass().getSimpleName());
    }
  }
}
