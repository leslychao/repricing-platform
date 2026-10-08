package ru.oritas.repricer.platform;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Reserved connections for queue claims and lease renewal, never business transactions. */
public final class JobControlConnections implements AutoCloseable {
  private final HikariDataSource pool;
  private final JdbcClient jdbc;

  public JobControlConnections(String url, String username, String password) {
    var configuration = new HikariConfig();
    configuration.setPoolName("job-control");
    configuration.setJdbcUrl(url);
    configuration.setUsername(username);
    configuration.setPassword(password);
    configuration.setMinimumIdle(3);
    configuration.setMaximumPoolSize(3);
    configuration.setConnectionTimeout(3000);
    configuration.setValidationTimeout(2000);
    configuration.setConnectionInitSql("SET statement_timeout='3s'; SET lock_timeout='500ms'");
    this.pool = new HikariDataSource(configuration);
    this.jdbc = JdbcClient.create(pool);
  }

  public JdbcClient jdbc() {
    return jdbc;
  }

  @Override
  public void close() {
    pool.close();
  }
}
