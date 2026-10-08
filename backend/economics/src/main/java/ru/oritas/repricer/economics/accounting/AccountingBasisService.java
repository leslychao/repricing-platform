package ru.oritas.repricer.economics.accounting;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;

/** One account generation prevents a published ledger edition from mixing declaration scales. */
@Service
public final class AccountingBasisService {
  private final JdbcClient jdbc;
  private final OutboxService outbox;

  public AccountingBasisService(JdbcClient jdbc, OutboxService outbox) {
    this.jdbc = jdbc;
    this.outbox = outbox;
  }

  public long current(Scope scope) {
    jdbc.sql(
            "SELECT id FROM marketplace_account WHERE organization_id=:org AND id=:account FOR"
                + " UPDATE")
        .param("org", scope.organizationId())
        .param("account", scope.requireAccount())
        .query(UUID.class)
        .single();
    return jdbc.sql(
            """
            SELECT revision FROM economics_accounting_basis WHERE organization_id=:org AND account_id=:account
            """)
        .param("org", scope.organizationId())
        .param("account", scope.accountId())
        .query(Long.class)
        .optional()
        .orElse(0L);
  }

  /** Called in the declaration publisher's account-locked transaction after its real change. */
  public void changed(Scope scope) {
    long revision =
        jdbc.sql(
                """
                INSERT INTO economics_accounting_basis(organization_id,account_id,revision)
                VALUES(:org,:account,1) ON CONFLICT(organization_id,account_id)
                  DO UPDATE SET revision=economics_accounting_basis.revision+1 RETURNING revision
                """)
            .param("org", scope.organizationId())
            .param("account", scope.requireAccount())
            .query(Long.class)
            .single();
    outbox.emit(
        scope,
        "FINANCIAL_BASIS:" + revision,
        "FINANCIAL_BASIS_CHANGED",
        new OutboxService.EntityChange("economics", scope.accountId(), revision));
  }
}
