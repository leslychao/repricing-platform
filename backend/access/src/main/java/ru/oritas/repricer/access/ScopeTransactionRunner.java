package ru.oritas.repricer.access;

import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.ScopeExecutor;

@Component
public final class ScopeTransactionRunner implements ScopeExecutor {
  private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
  private final JdbcClient jdbc;
  private final TransactionTemplate transactions;
  private final AuthorizationService authorization;

  public ScopeTransactionRunner(
      JdbcClient jdbc, PlatformTransactionManager transactions, AuthorizationService authorization) {
    this.jdbc = jdbc;
    this.transactions = new TransactionTemplate(transactions);
    this.authorization = authorization;
    this.transactions.setTimeout(5);
  }

  public <T> T run(Scope scope, Supplier<T> operation) {
    return execute(scope, Set.of(), operation);
  }

  public void run(Scope scope, Runnable operation) {
    run(scope, () -> {
      operation.run();
      return Boolean.TRUE;
    });
  }

  public <T> T runService(Scope scope, Set<String> permissions, Supplier<T> operation) {
    return execute(scope, permissions, operation);
  }

  @Override
  public <T> T execute(Scope scope, Set<String> servicePermissions, Supplier<T> operation) {
    Objects.requireNonNull(scope);
    Set<String> permissions = Set.copyOf(servicePermissions);
    Context previous = CURRENT.get();
    if (previous != null) {
      if (!previous.scope().equals(scope) || !previous.servicePermissions().equals(permissions)) {
        throw new IllegalStateException("Nested transaction cannot change authorization scope");
      }
      return operation.get();
    }
    return transactions.execute(status -> {
      CURRENT.set(new Context(scope, permissions));
      try {
        jdbc.sql("""
            SELECT set_config('app.subject_id',:subject,true),
              set_config('app.organization_id',:organization,true),
              set_config('app.account_id',:account,true),set_config('app.authorized','false',true)
            """)
            .param("subject", scope.subjectId().toString())
            .param("organization", scope.organizationId() == null ? "" : scope.organizationId().toString())
            .param("account", scope.accountId() == null ? "" : scope.accountId().toString())
            .query((row, index) -> Boolean.TRUE).single();
        if (permissions.isEmpty() && scope.organizationId() != null) {
          authorization.requireMembership(scope);
        }
        jdbc.sql("SELECT set_config('app.authorized','true',true)").query(String.class).single();
        return operation.get();
      } finally {
        CURRENT.remove();
      }
    });
  }

  static boolean serviceAllows(Scope scope, String permission) {
    Context context = CURRENT.get();
    return context != null && context.scope().equals(scope)
        && context.servicePermissions().contains(permission);
  }

  public static void requireCurrent(Scope scope) {
    Context context = CURRENT.get();
    if (context == null || !context.scope().equals(scope)) {
      throw new BusinessException("SCOPE_REQUIRED", 403, "Недопустимая область операции");
    }
  }

  private record Context(Scope scope, Set<String> servicePermissions) {}
}
