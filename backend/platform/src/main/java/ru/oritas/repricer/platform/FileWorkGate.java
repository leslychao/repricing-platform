package ru.oritas.repricer.platform;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Two globally leased file jobs; each may reserve at most 2 GiB of temporary disk. */
@Component
public final class FileWorkGate {
  private final JdbcClient jdbc;
  private final Clock clock;
  private final Map<UUID, Permit> active = new ConcurrentHashMap<>();

  public FileWorkGate(JdbcClient jdbc, Clock clock) {
    this.jdbc = jdbc;
    this.clock = clock;
  }

  public Optional<Permit> tryAcquire(Instant deadline) {
    if (!deadline.isAfter(clock.instant()) || deadline.isAfter(clock.instant().plusSeconds(1800))) {
      throw new IllegalArgumentException("File work requires a bounded deadline");
    }
    UUID token = UUID.randomUUID();
    boolean acquired = jdbc.sql("SELECT repricer_file_work(:token,'ACQUIRE')")
        .param("token", token).query(Boolean.class).single();
    if (!acquired) {
      return Optional.empty();
    }
    Permit permit = new Permit(token, deadline);
    active.put(token, permit);
    return Optional.of(permit);
  }

  @Scheduled(fixedDelay = 10000)
  public void renew() {
    for (Permit permit : active.values()) {
      if (!permit.valid || clock.instant().isAfter(permit.deadline)) {
        permit.valid = false;
        continue;
      }
      try {
        permit.valid = jdbc.sql("SELECT repricer_file_work(:token,'RENEW')")
            .param("token", permit.token).query(Boolean.class).single();
      } catch (RuntimeException failure) {
        permit.valid = false;
      }
    }
  }

  public final class Permit implements AutoCloseable {
    private final UUID token;
    private final Instant deadline;
    private volatile boolean valid = true;

    private Permit(UUID token, Instant deadline) {
      this.token = token;
      this.deadline = deadline;
    }

    public void requireValid() {
      if (!valid || Thread.currentThread().isInterrupted() || clock.instant().isAfter(deadline)) {
        throw new BusinessException("FILE_LEASE_EXPIRED", 409, "Право обработки файла утрачено");
      }
    }

    /** Used in the publication transaction, not only by the parser's local fast check. */
    public void confirm() {
      requireValid();
      if (!jdbc.sql("SELECT repricer_file_work(:token,'RENEW')")
          .param("token", token).query(Boolean.class).single()) {
        valid = false;
        requireValid();
      }
    }

    @Override
    public void close() {
      valid = false;
      active.remove(token);
      jdbc.sql("SELECT repricer_file_work(:token,'RELEASE')")
          .param("token", token).query(Boolean.class).single();
    }
  }
}
