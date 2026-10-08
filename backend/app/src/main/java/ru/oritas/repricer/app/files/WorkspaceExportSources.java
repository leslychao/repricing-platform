package ru.oritas.repricer.app.files;

import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.HistoryQuery;
import ru.oritas.repricer.platform.OperationService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

@Configuration
public class WorkspaceExportSources {
  @Bean
  TableExportSource operationExport(
      OperationService operations,
      AuthorizationService authorization,
      MarketplaceReadService marketplace) {
    return new TableExportSource() {
      @Override
      public String resource() {
        return "operations";
      }

      @Override
      public Set<String> permissions() {
        return Set.of("operation.read");
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        authorization.require(scope, "operation.read");
        return operations.projection(
            HistoryQuery.from(query),
            scope.accountId() == null ? ZoneOffset.UTC : marketplace.zoneId(scope),
            ids);
      }
    };
  }

  @Bean
  TableExportSource auditExport(
      AuditService audit, AuthorizationService authorization, MarketplaceReadService marketplace) {
    return new TableExportSource() {
      @Override
      public String resource() {
        return "audit";
      }

      @Override
      public Set<String> permissions() {
        return Set.of("audit.read");
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        authorization.require(scope, "audit.read");
        return audit.projection(
            scope,
            AuditService.Query.from(query),
            scope.accountId() == null ? ZoneOffset.UTC : marketplace.zoneId(scope),
            ids);
      }
    };
  }
}
