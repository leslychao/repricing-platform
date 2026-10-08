package ru.oritas.repricer.app.files;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.access.AccessQuery;
import ru.oritas.repricer.access.AccessService;
import ru.oritas.repricer.access.InvitationService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TableExportSource;

@Configuration
public class AccessExportSources {
  @Bean
  TableExportSource membersExport(AccessService access) {
    return new TableExportSource() {
      @Override
      public String resource() {
        return "memberships";
      }

      @Override
      public Set<String> permissions() {
        return Set.of("membership.read");
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        return access.memberProjection(scope, AccessQuery.from(query), ids);
      }
    };
  }

  @Bean
  TableExportSource accountAccessExport(AccessService access) {
    return new TableExportSource() {
      @Override
      public String resource() {
        return "account-access";
      }

      @Override
      public Set<String> permissions() {
        return Set.of("membership.read");
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        return access.accountProjection(scope, AccessQuery.from(query), ids);
      }
    };
  }

  @Bean
  TableExportSource invitationExport(InvitationService invitations) {
    return new TableExportSource() {
      @Override
      public String resource() {
        return "invitations";
      }

      @Override
      public Set<String> permissions() {
        return Set.of("membership.manage");
      }

      @Override
      public Projection projection(Scope scope, Query query, List<UUID> ids) {
        return invitations.projection(scope, AccessQuery.from(query), ids);
      }
    };
  }
}
