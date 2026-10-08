package ru.oritas.repricer.economics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.oritas.repricer.marketplace.MarketplaceReadService;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.TabularImportTarget;

@Configuration
public class AccountingImportTargets {
  @Bean
  TabularImportTarget costImportTarget(
      AccountingImportService imports, MarketplaceReadService marketplace) {
    return new Target(AccountingImportService.Kind.COSTS, imports, marketplace);
  }

  @Bean
  TabularImportTarget sellerCostImportTarget(
      AccountingImportService imports, MarketplaceReadService marketplace) {
    return new Target(AccountingImportService.Kind.SELLER_COSTS, imports, marketplace);
  }

  @Bean
  TabularImportTarget taxImportTarget(
      AccountingImportService imports, MarketplaceReadService marketplace) {
    return new Target(AccountingImportService.Kind.TAX, imports, marketplace);
  }

  private record Target(
      AccountingImportService.Kind importKind,
      AccountingImportService imports,
      MarketplaceReadService marketplace)
      implements TabularImportTarget {
    @Override
    public String kind() {
      return importKind.name();
    }

    @Override
    public Set<String> permissions() {
      return importKind == AccountingImportService.Kind.TAX
          ? Set.of("finance.write")
          : Set.of("finance.write", "catalog.read");
    }

    @Override
    public List<Column> columns() {
      var result = new ArrayList<Column>();
      if (importKind != AccountingImportService.Kind.TAX) {
        result.add(new Column("sku", Type.TEXT, true));
        result.add(
            new Column("amount", Type.DECIMAL, importKind == AccountingImportService.Kind.COSTS));
        result.add(
            new Column(
                "extraExpense",
                Type.DECIMAL,
                importKind == AccountingImportService.Kind.SELLER_COSTS));
      } else {
        result.add(new Column("rate", Type.DECIMAL, true));
      }
      result.add(new Column("validFrom", Type.DATE, true));
      result.add(new Column("validUntil", Type.DATE, false));
      return List.copyOf(result);
    }

    @Override
    public void begin(Scope scope, UUID importId) {
      imports.begin(scope, importId, importKind);
    }

    @Override
    public void stage(Scope scope, UUID importId, List<InputRow> rows) {
      if (importKind == AccountingImportService.Kind.TAX) {
        long revision =
            imports
                .capturedRevisions(scope, importId, List.of(scope.accountId()))
                .get(scope.accountId());
        imports.stageTax(
            scope,
            importId,
            rows.stream()
                .map(
                    row ->
                        new EconomicsService.TaxInput(
                            decimal(row, "rate"),
                            LocalDate.parse(row.value("validFrom")),
                            date(row, "validUntil"),
                            revision))
                .toList());
        return;
      }
      var identities =
          marketplace.resolveSkus(
              scope, rows.stream().map(row -> row.value("sku")).distinct().toList());
      for (InputRow row : rows) {
        if (!identities.containsKey(row.value("sku"))) {
          throw new BusinessException(
              "OFFER_NOT_FOUND", 422, "Неизвестный товар в строке " + row.number());
        }
      }
      var revisions = imports.capturedRevisions(scope, importId, List.copyOf(identities.values()));
      imports.stageCosts(
          scope,
          importId,
          rows.stream()
              .map(
                  row -> {
                    UUID offer = identities.get(row.value("sku"));
                    return new AccountingImportService.StagedCost(
                        offer,
                        LocalDate.parse(row.value("validFrom")),
                        date(row, "validUntil"),
                        decimal(row, "amount"),
                        decimal(row, "extraExpense"),
                        revisions.get(offer));
                  })
              .toList());
    }

    @Override
    public void seal(Scope scope, UUID importId) {
      imports.seal(scope, importId);
    }

    @Override
    public boolean prepareNext(Scope scope, UUID importId) {
      return imports.prepareNext(scope, importId);
    }

    @Override
    public void lockForPublication(Scope scope, UUID importId) {
      imports.lockForPublication(scope);
    }

    @Override
    public void commit(Scope scope, UUID importId) {
      imports.commit(scope, importId);
    }

    private static LocalDate date(InputRow row, String column) {
      return row.value(column) == null ? null : LocalDate.parse(row.value(column));
    }

    private static BigDecimal decimal(InputRow row, String column) {
      return row.value(column) == null ? null : new BigDecimal(row.value(column));
    }
  }
}
