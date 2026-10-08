package ru.oritas.repricer.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import ru.oritas.repricer.access.AccessService;
import ru.oritas.repricer.access.AuditService;
import ru.oritas.repricer.access.AuthorizationService;
import ru.oritas.repricer.access.IdentityService;
import ru.oritas.repricer.access.ScopeTransactionRunner;
import ru.oritas.repricer.economics.EconomicsService;
import ru.oritas.repricer.economics.accounting.AccountingBasisService;
import ru.oritas.repricer.economics.accounting.AccountingService;
import ru.oritas.repricer.economics.accounting.LedgerService;
import ru.oritas.repricer.economics.accounting.MonthlyReportService;
import ru.oritas.repricer.marketplace.FinancialSourceService;
import ru.oritas.repricer.marketplace.FinancialSourceService.FinancialFact;
import ru.oritas.repricer.marketplace.FinancialSourceService.Publication;
import ru.oritas.repricer.platform.BusinessException;
import ru.oritas.repricer.platform.IdempotencyService;
import ru.oritas.repricer.platform.JobRuntime;
import ru.oritas.repricer.platform.JsonCodec;
import ru.oritas.repricer.platform.OutboxRecipient;
import ru.oritas.repricer.platform.OutboxService;
import ru.oritas.repricer.platform.Scope;
import ru.oritas.repricer.platform.StoredFileService;
import ru.oritas.repricer.platform.TableExportSource;

/**
 * Financial editions use real owner transactions; only the vendor's normalized source is supplied.
 */
class LedgerIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17.5-alpine")
          .withDatabaseName("repricer")
          .withUsername("postgres")
          .withPassword("test-container-only");
  private static final LocalDate MONTH = LocalDate.of(2026, 9, 1);
  private static final Instant GENERATED = Instant.parse("2026-10-01T12:00:00Z");
  private static JdbcClient jdbc;
  private static ScopeTransactionRunner transactions;
  private static AuthorizationService authorization;
  private static OutboxService outbox;
  private static UUID organization;
  private static UUID admin;

  @Test
  void salesUseDeclaredDateBasisWhilePaymentsAndUnknownCompensationStayDistinct() {
    Scope scope = scope();
    UUID offer = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          UUID catalog = UUID.randomUUID();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                  VALUES(:id,:org,:account,'CATALOG','PUBLISHED')
                  """)
              .param("id", catalog)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                    observed_at,publication_id)
                  VALUES(:id,:org,:account,:external,:external,'Recognized sale',clock_timestamp(),:catalog)
                  """)
              .param("id", offer)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("external", offer.toString())
              .param("catalog", catalog)
              .update();
        });
    var source = mock(FinancialSourceService.class);
    var seed = publication(scope, source, GENERATED, "d", List.of(BigDecimal.ONE), true);
    var published =
        new Publication(
            seed.id(),
            seed.fromInclusive(),
            seed.untilExclusive(),
            seed.generatedAt(),
            3,
            Map.of("REVENUE", true),
            seed.digest(),
            seed.rawFileId(),
            "REALIZATION:campaign:DELIVERY_MONTH:" + YearMonth.from(MONTH));
    var settlement =
        new FinancialSourceService.SettlementEvidence(
            UUID.randomUUID(),
            BigDecimal.ONE,
            new BigDecimal("1000"),
            new BigDecimal("1000"),
            null,
            null,
            null,
            null,
            FinancialSourceService.InventoryDisposition.CONSUMED,
            null,
            new FinancialSourceService.CostBasisEvidence(
                MONTH, FinancialSourceService.CostBasisKind.DELIVERY_FALLBACK, seed.rawFileId()),
            null);
    var sale =
        new FinancialFact(
            1,
            UUID.randomUUID(),
            1,
            seed.digest(),
            offer,
            MONTH,
            "REVENUE",
            new BigDecimal("1000"),
            null,
            BigDecimal.ONE,
            null,
            seed.rawFileId(),
            settlement,
            null);
    var payment =
        new FinancialFact(
            2,
            UUID.randomUUID(),
            1,
            seed.digest(),
            null,
            MONTH,
            "PAYMENT",
            new BigDecimal("1000"),
            null,
            null,
            null,
            seed.rawFileId(),
            null,
            null);
    var compensation =
        new FinancialFact(
            3,
            UUID.randomUUID(),
            1,
            seed.digest(),
            offer,
            MONTH,
            "COMPENSATION",
            new BigDecimal("50"),
            null,
            null,
            null,
            seed.rawFileId(),
            null,
            null);
    when(source.publication(scope, seed.id())).thenReturn(published);
    when(source.page(eq(scope), eq(seed.id()), anyLong(), anyInt()))
        .thenAnswer(
            invocation ->
                List.of(sale, payment, compensation).stream()
                    .filter(fact -> fact.rowNumber() > invocation.getArgument(2, Long.class))
                    .toList());
    var ledger = ledger(source);
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, seed.id())));
    assertEquals(
        2L,
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        "SELECT count(*) FROM economics_financial_event WHERE current_revision AND"
                            + " NOT complete")
                    .query(Long.class)
                    .single()));
    assertMoney(
        "1000",
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        "SELECT payment_amount FROM economics_financial_event WHERE"
                            + " current_revision AND component='PAYMENT'")
                    .query(BigDecimal.class)
                    .single()));
    assertMoney(
        "0",
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        "SELECT income FROM economics_financial_event WHERE current_revision AND"
                            + " component='PAYMENT'")
                    .query(BigDecimal.class)
                    .single()));
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("600"), new BigDecimal("40"), MONTH, null, 0));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(new BigDecimal("0.06"), MONTH, null, 0));
        });
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, seed.id())));
    assertMoney("300", revenueProfit(scope));
    transactions.run(
        scope,
        () ->
            economics.publishCost(
                scope,
                new EconomicsService.CostInput(
                    offer, new BigDecimal("650"), new BigDecimal("40"), MONTH, null, 1)));
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, seed.id())));
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, seed.id())));
    assertMoney("250", revenueProfit(scope));
    assertEquals(
        3L,
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT count(*) FROM economics_financial_event WHERE component='REVENUE'")
                    .query(Long.class)
                    .single()));
    assertMoney(
        "300",
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        "SELECT profit FROM economics_financial_event WHERE component='REVENUE' AND"
                            + " accounting_revision=3")
                    .query(BigDecimal.class)
                    .single()));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("700"), new BigDecimal("40"), MONTH, null, 2));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(new BigDecimal("0.08"), MONTH, null, 1));
          economics.publishTax(
              scope,
              new EconomicsService.TaxInput(new BigDecimal("0.20"), MONTH.plusMonths(1), null, 2));
        });
    // The original sale is deliberately not rebuilt first. A refund uses its original date,
    // current declarations at that date, and never the refund month's tax or old monetary edition.
    var refundSeed =
        publication(scope, source, GENERATED.plusSeconds(60), "e", List.of(BigDecimal.ONE), true);
    var refundPublication =
        new Publication(
            refundSeed.id(),
            MONTH.plusMonths(1),
            MONTH.plusMonths(2),
            refundSeed.generatedAt(),
            1,
            Map.of("REFUND", true),
            refundSeed.digest(),
            refundSeed.rawFileId(),
            "REALIZATION:refund:DELIVERY_MONTH:" + YearMonth.from(MONTH.plusMonths(1)));
    var refundEvidence =
        new FinancialSourceService.SettlementEvidence(
            settlement.originalOrderLineId(),
            new BigDecimal("0.5"),
            new BigDecimal("500"),
            new BigDecimal("500"),
            true,
            true,
            true,
            true,
            FinancialSourceService.InventoryDisposition.RESTORED,
            UUID.randomUUID(),
            null,
            null);
    var refund =
        new FinancialFact(
            1,
            UUID.randomUUID(),
            1,
            refundSeed.digest(),
            offer,
            MONTH.plusMonths(1),
            "REFUND",
            new BigDecimal("500"),
            null,
            new BigDecimal("0.5"),
            null,
            refundSeed.rawFileId(),
            refundEvidence,
            null);
    when(source.publication(scope, refundSeed.id())).thenReturn(refundPublication);
    when(source.page(eq(scope), eq(refundSeed.id()), anyLong(), anyInt()))
        .thenAnswer(
            invocation ->
                invocation.getArgument(2, Long.class) == 0L ? List.of(refund) : List.of());
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, refundSeed.id())));
    var refundAmounts =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        "SELECT cost,tax,profit,expenses FROM economics_financial_event WHERE"
                            + " current_revision AND component='REFUND'")
                    .query(
                        (rs, row) ->
                            List.of(
                                rs.getBigDecimal(1),
                                rs.getBigDecimal(2),
                                rs.getBigDecimal(3),
                                rs.getBigDecimal(4)))
                    .single());
    assertMoney("-350", refundAmounts.get(0));
    assertMoney("-40", refundAmounts.get(1));
    assertMoney("-110", refundAmounts.get(2));
    assertMoney("0", refundAmounts.get(3));
  }

  @Test
  void separateReturnsRestoreOriginalProportionOnceAndRejectAccumulatedExcess() {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var source = mock(FinancialSourceService.class);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("100"), new BigDecimal("7"), MONTH, MONTH.plusDays(1), 0));
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("200"), new BigDecimal("7"), MONTH.plusDays(1), null, 1));
          economics.publishTax(
              scope,
              new EconomicsService.TaxInput(new BigDecimal("0.06"), MONTH, MONTH.plusDays(1), 0));
          economics.publishTax(
              scope,
              new EconomicsService.TaxInput(
                  new BigDecimal("0.08"), MONTH.plusDays(1), MONTH.plusMonths(1), 1));
          economics.publishTax(
              scope,
              new EconomicsService.TaxInput(new BigDecimal("0.20"), MONTH.plusMonths(1), null, 2));
        });
    UUID line = UUID.randomUUID();
    var sales = returnPublication(scope, source, "sales", GENERATED, "a");
    var first = returnFact(sales, 1, offer, line, null, "REVENUE", MONTH, "1", "1000", false);
    var second =
        returnFact(sales, 2, offer, line, null, "REVENUE", MONTH.plusDays(1), "1", "1000", false);
    supplyReturnFacts(scope, source, sales, List.of(first, second));
    var ledger = ledger(source);
    finishLedger(scope, ledger, sales.id());
    UUID returnId = UUID.randomUUID();
    var money = returnPublication(scope, source, "money-one", GENERATED.plusSeconds(1), "b");
    var refunded =
        returnFact(
            money, 1, offer, line, returnId, "REFUND", MONTH.plusMonths(1), "1", "1000", false);
    supplyReturnFacts(scope, source, money, List.of(refunded));
    finishLedger(scope, ledger, money.id());
    assertReturnAmounts(scope, refunded.id(), "-1000", "0", "-70");
    var physical = returnPublication(scope, source, "physical-one", GENERATED.plusSeconds(2), "c");
    var received =
        returnFact(
            physical,
            1,
            offer,
            line,
            returnId,
            "RETURN_PHYSICAL",
            MONTH.plusMonths(1).plusDays(1),
            "1",
            null,
            true);
    supplyReturnFacts(scope, source, physical, List.of(received));
    finishLedger(scope, ledger, physical.id());
    assertReturnAmounts(scope, received.id(), "0", "-150", "0");
    assertReturnAmounts(scope, refunded.id(), "-1000", "0", "-70");
    // Replaying a fully confirmed physical source does not consume another unit of the sale.
    finishLedger(scope, ledger, physical.id());
    assertReturnAmounts(scope, received.id(), "0", "-150", "0");
    var finalReturn = returnPublication(scope, source, "money-two", GENERATED.plusSeconds(3), "d");
    var last =
        returnFact(
            finalReturn,
            1,
            offer,
            line,
            UUID.randomUUID(),
            "REFUND",
            MONTH.plusMonths(1).plusDays(2),
            "1",
            "1000",
            true);
    supplyReturnFacts(scope, source, finalReturn, List.of(last));
    finishLedger(scope, ledger, finalReturn.id());
    assertReturnAmounts(scope, last.id(), "-1000", "-150", "-70");
    assertMoney(
        "14",
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        "SELECT sum(expenses) FROM economics_financial_event WHERE"
                            + " current_revision")
                    .query(BigDecimal.class)
                    .single()));
    var excess = returnPublication(scope, source, "excess", GENERATED.plusSeconds(4), "e");
    supplyReturnFacts(
        scope,
        source,
        excess,
        List.of(
            returnFact(
                excess,
                1,
                offer,
                line,
                UUID.randomUUID(),
                "REFUND",
                MONTH.plusMonths(1).plusDays(3),
                "1",
                "1000",
                true)));
    var rejected =
        assertThrows(BusinessException.class, () -> finishLedger(scope, ledger, excess.id()));
    assertEquals("RETURN_EXCEEDS_ORIGINAL", rejected.code());
    assertReturnAmounts(scope, last.id(), "-1000", "-150", "-70");
    // Correcting an early proof rebuilds the complete connected set, not just this cohort.
    var correction =
        returnPublication(scope, source, "physical-one", GENERATED.plusSeconds(5), "f");
    var noStock =
        new FinancialFact(
            1,
            received.id(),
            2,
            correction.digest(),
            offer,
            received.accountingDate(),
            "RETURN_PHYSICAL",
            null,
            null,
            BigDecimal.ONE,
            null,
            correction.rawFileId(),
            new FinancialSourceService.SettlementEvidence(
                line,
                BigDecimal.ONE,
                null,
                null,
                null,
                true,
                true,
                false,
                FinancialSourceService.InventoryDisposition.UNKNOWN,
                returnId,
                null,
                null),
            null);
    supplyReturnFacts(scope, source, correction, List.of(noStock));
    finishLedger(scope, ledger, correction.id());
    assertReturnAmounts(scope, received.id(), "0", "0", "0");
    assertReturnAmounts(scope, last.id(), "-1000", "-150", "-70");
    assertEquals(
        5L,
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT count(*) FROM economics_financial_event WHERE current_revision")
                    .query(Long.class)
                    .single()));
  }

  @Test
  void finalReturnRetainsEighteenDigitRemainderForCostAndOriginalTax() {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var source = mock(FinancialSourceService.class);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, BigDecimal.ZERO, BigDecimal.ZERO, MONTH, MONTH.plusDays(1), 0));
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("0.5"), BigDecimal.ZERO, MONTH.plusDays(1), null, 1));
          economics.publishTax(
              scope,
              new EconomicsService.TaxInput(new BigDecimal("0.1"), MONTH, MONTH.plusDays(1), 0));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(BigDecimal.ZERO, MONTH.plusDays(1), null, 1));
        });
    UUID line = UUID.randomUUID();
    var sales = returnPublication(scope, source, "remainder-sales", GENERATED, "1");
    supplyReturnFacts(
        scope,
        source,
        sales,
        List.of(
            returnFact(sales, 1, offer, line, null, "REVENUE", MONTH, "1", "1", false),
            returnFact(
                sales, 2, offer, line, null, "REVENUE", MONTH.plusDays(1), "2", "2", false)));
    var ledger = ledger(source);
    finishLedger(scope, ledger, sales.id());
    var returns =
        returnPublication(scope, source, "remainder-returns", GENERATED.plusSeconds(1), "2");
    var first =
        returnFact(
            returns,
            1,
            offer,
            line,
            UUID.randomUUID(),
            "REFUND",
            MONTH.plusMonths(1),
            "1",
            "1",
            true);
    var middle =
        returnFact(
            returns,
            2,
            offer,
            line,
            UUID.randomUUID(),
            "REFUND",
            MONTH.plusMonths(1).plusDays(1),
            "1",
            "1",
            true);
    var last =
        returnFact(
            returns,
            3,
            offer,
            line,
            UUID.randomUUID(),
            "REFUND",
            MONTH.plusMonths(1).plusDays(2),
            "1",
            "1",
            true);
    supplyReturnFacts(scope, source, returns, List.of(first, middle, last));
    finishLedger(scope, ledger, returns.id());
    assertReturnAmounts(scope, first.id(), "-1", "-0.333333333333333333", "-0.033333333333333333");
    assertReturnAmounts(scope, middle.id(), "-1", "-0.333333333333333334", "-0.033333333333333334");
    assertReturnAmounts(scope, last.id(), "-1", "-0.333333333333333333", "-0.033333333333333333");
    var sums =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        "SELECT sum(cost),sum(tax),sum(income) FROM economics_financial_event WHERE"
                            + " current_revision")
                    .query(
                        (rs, row) ->
                            List.of(rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getBigDecimal(3)))
                    .single());
    sums.forEach(value -> assertMoney("0", value));
  }

  @Test
  void fiveEqualRefundsReverseThreeMicroUnitsOfTaxWithoutRejectingValidCoverage() {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var source = mock(FinancialSourceService.class);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, BigDecimal.ZERO, BigDecimal.ZERO, MONTH, null, 0));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(new BigDecimal("0.000003"), MONTH, null, 0));
        });
    UUID line = UUID.randomUUID();
    var sale = returnPublication(scope, source, "micro-sale", GENERATED, "a");
    supplyReturnFacts(
        scope,
        source,
        sale,
        List.of(
            returnFact(
                sale, 1, offer, line, null, "REVENUE", MONTH, "5", "0.000000000001", false)));
    var ledger = ledger(source);
    finishLedger(scope, ledger, sale.id());
    var returns = returnPublication(scope, source, "micro-refunds", GENERATED.plusSeconds(1), "b");
    List<FinancialFact> facts = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      facts.add(
          returnFact(
              returns,
              index,
              offer,
              line,
              UUID.randomUUID(),
              "REFUND",
              MONTH.plusDays(index),
              "1",
              "0.0000000000002",
              false));
    }
    supplyReturnFacts(scope, source, returns, facts);
    finishLedger(scope, ledger, returns.id());
    assertReturnAmounts(scope, facts.get(0).id(), "-0.0000000000002", "0", "-0.000000000000000001");
    assertReturnAmounts(scope, facts.get(1).id(), "-0.0000000000002", "0", "0");
    assertReturnAmounts(scope, facts.get(4).id(), "-0.0000000000002", "0", "-0.000000000000000001");
    var tax =
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT sum(tax) FROM economics_financial_event WHERE current_revision")
                    .query(BigDecimal.class)
                    .single());
    assertMoney("0", tax);
    finishLedger(scope, ledger, returns.id());
    assertReturnAmounts(scope, facts.get(4).id(), "-0.0000000000002", "0", "-0.000000000000000001");
  }

  private enum ReturnLimit {
    REVENUE,
    TAX_BASE,
    REFUNDED_QUANTITY,
    RESTORED_QUANTITY
  }

  @ParameterizedTest
  @EnumSource(ReturnLimit.class)
  void unknownReturnPartDoesNotEraseTheKnownAccumulatedLimit(ReturnLimit limit) {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var source = mock(FinancialSourceService.class);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, BigDecimal.ZERO, BigDecimal.ZERO, MONTH, null, 0));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(BigDecimal.ZERO, MONTH, null, 0));
        });
    UUID line = UUID.randomUUID();
    var sale = returnPublication(scope, source, "bounded-sale", GENERATED, "a");
    supplyReturnFacts(
        scope,
        source,
        sale,
        List.of(returnFact(sale, 1, offer, line, null, "REVENUE", MONTH, "2", "1000", false)));
    var ledger = ledger(source);
    finishLedger(scope, ledger, sale.id());
    var returns = returnPublication(scope, source, "unknown-return", GENERATED.plusSeconds(1), "b");
    boolean physical = limit == ReturnLimit.RESTORED_QUANTITY;
    BigDecimal quantity =
        limit == ReturnLimit.REFUNDED_QUANTITY || physical
            ? new BigDecimal("1.5")
            : new BigDecimal("0.1");
    BigDecimal money = limit == ReturnLimit.REVENUE ? new BigDecimal("800") : BigDecimal.ZERO;
    BigDecimal base = limit == ReturnLimit.TAX_BASE ? new BigDecimal("800") : BigDecimal.ZERO;
    var unknown = returnLimitFact(returns, 1, offer, line, physical, null, null, null);
    var known = returnLimitFact(returns, 2, offer, line, physical, quantity, money, base);
    supplyReturnFacts(scope, source, returns, List.of(unknown, known));
    finishLedger(scope, ledger, returns.id());
    var excess = returnPublication(scope, source, "known-excess", GENERATED.plusSeconds(2), "c");
    supplyReturnFacts(
        scope,
        source,
        excess,
        List.of(returnLimitFact(excess, 1, offer, line, physical, quantity, money, base)));
    var failure =
        assertThrows(BusinessException.class, () -> finishLedger(scope, ledger, excess.id()));
    assertEquals("RETURN_EXCEEDS_ORIGINAL", failure.code());
    long currentRows =
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT count(*) FROM economics_financial_event WHERE current_revision")
                    .query(Long.class)
                    .single());
    assertEquals(3, currentRows);
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    "SELECT complete FROM economics_financial_event WHERE current_revision AND"
                        + " source_id=:id")
                .param("id", unknown.id())
                .query(
                    (rs, row) -> {
                      assertFalse(rs.getBoolean(1));
                      return rs.getBoolean(1);
                    })
                .single());
  }

  private static FinancialFact returnLimitFact(
      Publication publication,
      long ordinal,
      UUID offer,
      UUID line,
      boolean physical,
      BigDecimal quantity,
      BigDecimal income,
      BigDecimal taxBase) {
    var evidence =
        new FinancialSourceService.SettlementEvidence(
            line,
            quantity,
            income,
            taxBase,
            physical ? null : true,
            physical,
            physical,
            physical,
            physical
                ? FinancialSourceService.InventoryDisposition.RESTORED
                : FinancialSourceService.InventoryDisposition.CONSUMED,
            UUID.randomUUID(),
            null,
            null);
    return new FinancialFact(
        ordinal,
        UUID.randomUUID(),
        1,
        publication.digest(),
        offer,
        MONTH.plusDays(
            publication.generatedAt().getEpochSecond() - GENERATED.getEpochSecond() + ordinal),
        physical ? "RETURN_PHYSICAL" : "REFUND",
        income,
        null,
        quantity,
        null,
        publication.rawFileId(),
        evidence,
        null);
  }

  private static void finishLedger(Scope scope, LedgerService ledger, UUID source) {
    for (int attempt = 0; attempt < 20; attempt++) {
      if (transactions.run(scope, () -> ledger.prepareNext(scope, source))) {
        return;
      }
    }
    throw new AssertionError("Bounded financial fixture did not publish");
  }

  private static Publication returnPublication(
      Scope scope, FinancialSourceService source, String cohort, Instant generated, String digest) {
    var seed = publication(scope, source, generated, digest, List.of(), true);
    return new Publication(
        seed.id(),
        MONTH,
        MONTH.plusMonths(1),
        generated,
        0,
        Map.of(),
        seed.digest(),
        seed.rawFileId(),
        "REALIZATION:" + cohort);
  }

  private static void supplyReturnFacts(
      Scope scope,
      FinancialSourceService source,
      Publication publication,
      List<FinancialFact> facts) {
    var full =
        new Publication(
            publication.id(),
            publication.fromInclusive(),
            publication.untilExclusive(),
            publication.generatedAt(),
            facts.size(),
            publication.completeByComponent(),
            publication.digest(),
            publication.rawFileId(),
            publication.cohortKey());
    when(source.publication(scope, publication.id())).thenReturn(full);
    when(source.page(eq(scope), eq(publication.id()), anyLong(), anyInt()))
        .thenAnswer(
            invocation -> {
              long after = invocation.getArgument(2, Long.class);
              int limit = invocation.getArgument(3, Integer.class);
              return facts.stream().filter(fact -> fact.rowNumber() > after).limit(limit).toList();
            });
  }

  private static FinancialFact returnFact(
      Publication publication,
      long number,
      UUID offer,
      UUID line,
      UUID returned,
      String kind,
      LocalDate day,
      String quantity,
      String amount,
      boolean restored) {
    BigDecimal units = new BigDecimal(quantity);
    BigDecimal money = amount == null ? null : new BigDecimal(amount);
    var evidence =
        new FinancialSourceService.SettlementEvidence(
            line,
            units,
            money,
            money,
            kind.equals("REFUND") ? true : null,
            restored,
            restored,
            restored,
            restored
                ? FinancialSourceService.InventoryDisposition.RESTORED
                : FinancialSourceService.InventoryDisposition.CONSUMED,
            returned,
            kind.equals("REVENUE")
                ? new FinancialSourceService.CostBasisEvidence(
                    day,
                    FinancialSourceService.CostBasisKind.DELIVERY_FALLBACK,
                    publication.rawFileId())
                : null,
            null);
    return new FinancialFact(
        number,
        UUID.randomUUID(),
        1,
        publication.digest(),
        offer,
        day,
        kind,
        money,
        null,
        units,
        null,
        publication.rawFileId(),
        evidence,
        null);
  }

  private static void assertReturnAmounts(
      Scope scope, UUID source, String income, String cost, String tax) {
    var amounts =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        SELECT income,cost,tax,expenses FROM economics_financial_event
                        WHERE current_revision AND source_id=:source
                        """)
                    .param("source", source)
                    .query(
                        (rs, row) ->
                            List.of(
                                rs.getBigDecimal(1),
                                rs.getBigDecimal(2),
                                rs.getBigDecimal(3),
                                rs.getBigDecimal(4)))
                    .single());
    assertMoney(income, amounts.get(0));
    assertMoney(cost, amounts.get(1));
    assertMoney(tax, amounts.get(2));
    assertMoney("0", amounts.get(3));
  }

  @Test
  void serviceFactsReplaceOnlyProvenCoveredUnitsIncludingZeroAndUnknownScope() {
    Scope scope = scope();
    var source = mock(FinancialSourceService.class);
    var ledger = ledger(source);
    UUID serviceScope = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    UUID actualId = UUID.randomUUID();
    var estimated = returnPublication(scope, source, "estimate", GENERATED, "a");
    supplyReturnFacts(
        scope,
        source,
        estimated,
        List.of(serviceFact(estimated, estimateId, "100", serviceScope, true, "0", "2", null)));
    finishLedger(scope, ledger, estimated.id());
    assertServiceAmounts(scope, "0", "100");
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    var preliminary =
        transactions.run(
            scope,
            () ->
                economics.financialPage(
                    scope,
                    0,
                    50,
                    MONTH,
                    MONTH.plusMonths(1),
                    null,
                    "",
                    "",
                    "PRELIMINARY",
                    "status",
                    "asc"));
    assertEquals(1, preliminary.total());
    var row = preliminary.items().getFirst();
    assertTrue(row.complete());
    assertEquals("PRELIMINARY", row.certainty());
    assertEquals("PRELIMINARY", row.status());
    assertMoney("100", row.expenses());
    var confirmed =
        transactions.run(
            scope,
            () ->
                economics.financialPage(
                    scope,
                    0,
                    50,
                    MONTH,
                    MONTH.plusMonths(1),
                    null,
                    "",
                    "",
                    "CONFIRMED",
                    "status",
                    "asc"));
    assertEquals(0, confirmed.total());
    var exportQuery =
        new TableExportSource.Query(
            "",
            Map.of(
                "from",
                MONTH.toString(),
                "to",
                MONTH.plusMonths(1).minusDays(1).toString(),
                "status",
                "PRELIMINARY"),
            "status,asc",
            0,
            50);
    var export =
        transactions.run(
            scope,
            () -> economics.financialEventsExport().projection(scope, exportQuery, List.of()));
    var exported =
        transactions.run(
            scope,
            () ->
                jdbc.sql(export.sql())
                    .params(export.parameters())
                    .query(
                        (rs, index) ->
                            new JsonCodec().decode(rs.getString("cells"), String[].class))
                    .single());
    assertEquals("PRELIMINARY", exported[7]);
    assertEquals("COMPLETE", exported[8]);
    assertEquals("PRELIMINARY", exported[9]);
    when(source.coverageEvidence(scope, YearMonth.from(MONTH)))
        .thenReturn(
            new FinancialSourceService.CoverageEvidence(
                Map.of("SERVICE", true, "REVENUE", true, "REFUND", true, "COMPENSATION", true),
                java.util.Set.of(estimated.id())));
    finishLedger(scope, ledger, estimated.id());
    var summary =
        transactions.run(
            scope,
            () ->
                new MonthlyReportService(jdbc, authorization, source)
                    .summary(scope, YearMonth.from(MONTH)));
    assertEquals("INCOMPLETE", summary.coverage().status());
    assertEquals(1, summary.coverage().preliminaryEvents());
    assertEquals("PRELIMINARY", summary.breakdown().getFirst().certainty());

    var partial = returnPublication(scope, source, "actual", GENERATED.plusSeconds(1), "b");
    supplyReturnFacts(
        scope,
        source,
        partial,
        List.of(serviceFact(partial, actualId, "60", serviceScope, false, "0", "1", null)));
    finishLedger(scope, ledger, partial.id());
    assertServiceAmounts(scope, "60", "50");
    assertMoney("110", currentExpense(scope));

    var full = returnPublication(scope, source, "actual", GENERATED.plusSeconds(2), "c");
    supplyReturnFacts(
        scope,
        source,
        full,
        List.of(serviceFact(full, actualId, "90", serviceScope, false, "0", "2", null)));
    finishLedger(scope, ledger, full.id());
    assertServiceAmounts(scope, "90", "0");
    finishLedger(scope, ledger, full.id());
    assertMoney("90", currentExpense(scope));

    var zero = returnPublication(scope, source, "actual", GENERATED.plusSeconds(3), "d");
    supplyReturnFacts(
        scope,
        source,
        zero,
        List.of(serviceFact(zero, actualId, "0", serviceScope, false, "0", "2", null)));
    finishLedger(scope, ledger, zero.id());
    assertServiceAmounts(scope, "0", "0");

    var unrelated =
        returnPublication(scope, source, "unknown-scope", GENERATED.plusSeconds(4), "e");
    var unknown = fact(unrelated, 1, new BigDecimal("90"), MONTH);
    supplyReturnFacts(scope, source, unrelated, List.of(unknown));
    finishLedger(scope, ledger, unrelated.id());
    var another =
        returnPublication(scope, source, "another-estimate", GENERATED.plusSeconds(5), "f");
    supplyReturnFacts(
        scope,
        source,
        another,
        List.of(
            serviceFact(
                another, UUID.randomUUID(), "100", UUID.randomUUID(), true, "0", "2", null)));
    finishLedger(scope, ledger, another.id());
    assertServiceAmounts(scope, "90", "100");
    assertMoney("190", currentExpense(scope));
    long allocated =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        SELECT count(*) FROM economics_financial_event WHERE current_revision AND offer_id IS NOT NULL
                        """)
                    .query(Long.class)
                    .single());
    assertEquals(0, allocated);
  }

  @Test
  void allocatedServicesPublishAllRecipientsAtomicallyAndRejectOverlappingCoverage() {
    Scope scope = scope();
    var source = mock(FinancialSourceService.class);
    var ledger = ledger(source);
    List<FinancialSourceService.AllocationTarget> targets = new ArrayList<>();
    for (int index = 0; index < 101; index++) {
      targets.add(new FinancialSourceService.AllocationTarget(monthlyOffer(scope), BigDecimal.ONE));
    }
    var allocation =
        new FinancialSourceService.AllocationEvidence("confirmed-order-units", targets);
    UUID serviceScope = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    var estimate = returnPublication(scope, source, "allocated-estimate", GENERATED, "1");
    supplyReturnFacts(
        scope,
        source,
        estimate,
        List.of(
            serviceFact(estimate, estimateId, "100", serviceScope, true, "0", "2", allocation)));
    finishLedger(scope, ledger, estimate.id());
    var actual =
        returnPublication(scope, source, "allocated-actual", GENERATED.plusSeconds(1), "2");
    supplyReturnFacts(
        scope,
        source,
        actual,
        List.of(
            serviceFact(
                actual, UUID.randomUUID(), "90", serviceScope, false, "0", "2", allocation)));
    assertFalse(transactions.run(scope, () -> ledger.prepareNext(scope, actual.id())));
    assertMoney("100", currentExpense(scope));
    finishLedger(scope, ledger, actual.id());
    assertMoney("90", currentExpense(scope));
    long allocated =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        SELECT count(*) FROM economics_financial_event WHERE current_revision AND offer_id IS NOT NULL
                        """)
                    .query(Long.class)
                    .single());
    assertEquals(202, allocated);
    finishLedger(scope, ledger, actual.id());
    assertMoney("90", currentExpense(scope));

    var overlap = returnPublication(scope, source, "overlap", GENERATED.plusSeconds(2), "3");
    supplyReturnFacts(
        scope,
        source,
        overlap,
        List.of(
            serviceFact(
                overlap, UUID.randomUUID(), "10", serviceScope, false, "1", "2", allocation)));
    var conflict =
        assertThrows(BusinessException.class, () -> finishLedger(scope, ledger, overlap.id()));
    assertEquals("SERVICE_SCOPE_CONFLICT", conflict.code());
    assertMoney("90", currentExpense(scope));
  }

  @Test
  void uncoveredKnownServiceIsConfirmedButDoesNotClaimACompleteMonth() {
    Scope scope = scope();
    var source = mock(FinancialSourceService.class);
    var ledger = ledger(source);
    UUID serviceScope = UUID.randomUUID();
    UUID factId = UUID.randomUUID();
    var partial = returnPublication(scope, source, "uncovered", GENERATED, "4");
    supplyReturnFacts(
        scope,
        source,
        partial,
        List.of(serviceFact(partial, factId, "60", serviceScope, false, "0", "1", null)));
    when(source.coverageEvidence(scope, YearMonth.from(MONTH)))
        .thenReturn(
            new FinancialSourceService.CoverageEvidence(
                Map.of("SERVICE", true, "REVENUE", true, "REFUND", true, "COMPENSATION", true),
                java.util.Set.of(partial.id())));
    finishLedger(scope, ledger, partial.id());
    finishLedger(scope, ledger, partial.id());
    assertServiceAmounts(scope, "60", "0");
    var reports = new MonthlyReportService(jdbc, authorization, source);
    var coverage = transactions.run(scope, () -> reports.coverage(scope, YearMonth.from(MONTH)));
    assertEquals("INCOMPLETE", coverage.status());
    assertEquals("SERVICE_COVERAGE_INCOMPLETE", coverage.reason());

    var unknown = returnPublication(scope, source, "uncovered", GENERATED.plusSeconds(1), "5");
    supplyReturnFacts(
        scope,
        source,
        unknown,
        List.of(serviceFact(unknown, factId, null, serviceScope, false, "0", "2", null)));
    finishLedger(scope, ledger, unknown.id());
    var amounts =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        SELECT expenses,complete FROM economics_financial_event WHERE current_revision AND source_id=:id
                        """)
                    .param("id", factId)
                    .query((rs, row) -> new UnknownService(rs.getBigDecimal(1), rs.getBoolean(2)))
                    .single());
    assertNull(amounts.expense());
    assertFalse(amounts.complete());
  }

  private record UnknownService(BigDecimal expense, boolean complete) {}

  private static FinancialFact serviceFact(
      Publication publication,
      UUID id,
      String amount,
      UUID serviceScope,
      boolean estimated,
      String from,
      String until,
      FinancialSourceService.AllocationEvidence allocation) {
    var evidence =
        new FinancialSourceService.ServiceEvidence(
            serviceScope,
            estimated
                ? FinancialSourceService.ServiceValueKind.ESTIMATE
                : FinancialSourceService.ServiceValueKind.ACTUAL,
            "DELIVERED_UNIT",
            new BigDecimal("2"),
            new BigDecimal(from),
            new BigDecimal(until),
            MONTH,
            publication.rawFileId(),
            allocation);
    return new FinancialFact(
        1,
        id,
        1,
        publication.digest(),
        null,
        MONTH,
        "SERVICE",
        amount == null ? null : new BigDecimal(amount),
        "STORAGE",
        null,
        null,
        publication.rawFileId(),
        null,
        evidence);
  }

  private static void assertServiceAmounts(Scope scope, String confirmed, String preliminary) {
    var amounts =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        SELECT coalesce(sum(expenses) FILTER(WHERE preliminary=false),0),
                          coalesce(sum(expenses) FILTER(WHERE preliminary=true),0)
                        FROM economics_financial_event WHERE current_revision AND component LIKE 'SERVICE:%'
                        """)
                    .query((rs, row) -> List.of(rs.getBigDecimal(1), rs.getBigDecimal(2)))
                    .single());
    assertMoney(confirmed, amounts.get(0));
    assertMoney(preliminary, amounts.get(1));
  }

  @Test
  void recognitionUpgradeRebuildsLegacyKnownCostWithoutChangingSourceOrDeclarations() {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var source = mock(FinancialSourceService.class);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("100"), BigDecimal.ZERO, MONTH, null, 0));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(new BigDecimal("0.1"), MONTH, null, 0));
        });
    var publication = returnPublication(scope, source, "legacy-cost", GENERATED, "f");
    UUID sourceId = UUID.randomUUID();
    var fact =
        compensationFact(
            publication, sourceId, offer, null, "300", MONTH.plusDays(4), "COMPENSATION");
    supplyReturnFacts(scope, source, publication, List.of(fact));
    UUID legacy = UUID.randomUUID();
    UUID legacyEvent = UUID.randomUUID();
    long basisRevision =
        transactions.run(
            scope,
            () -> {
              long revision = new AccountingBasisService(jdbc, outbox).current(scope);
              jdbc.sql(
                      """
                      INSERT INTO economics_ledger_publication(organization_id,account_id,id,cohort_key,generated_at,
                        source_digest,from_day,until_day,expected_rows,processed_rows,state,raw_file_id,
                        source_publication_id,accounting_revision,component_coverage,recognition_complete,recognition_version)
                      VALUES(:org,:account,:id,:cohort,:generated,:digest,:start,:until,1,1,'PUBLISHED',:raw,
                        :source,:revision,'{}',true,1)
                      """)
                  .param("org", scope.organizationId())
                  .param("account", scope.accountId())
                  .param("id", legacy)
                  .param("cohort", publication.cohortKey())
                  .param("generated", java.sql.Timestamp.from(GENERATED))
                  .param("digest", publication.digest())
                  .param("start", MONTH)
                  .param("until", MONTH.plusMonths(1))
                  .param("raw", publication.rawFileId())
                  .param("source", publication.id())
                  .param("revision", revision)
                  .update();
              new AccountingService(jdbc).stageFacts(scope, legacy, List.of(fact));
              jdbc.sql(
                      """
                      INSERT INTO economics_recognition_guard(organization_id,account_id,source_id,component)
                      VALUES(:org,:account,:source,'COMPENSATION')
                      """)
                  .param("org", scope.organizationId())
                  .param("account", scope.accountId())
                  .param("source", sourceId)
                  .update();
              jdbc.sql(
                      """
                      INSERT INTO economics_financial_event(organization_id,account_id,id,source_id,component,
                        source_revision,source_digest,offer_id,accounting_date,income,cost,expenses,tax,profit,
                        complete,current_revision,publication_id,preparation_id,accounting_revision)
                      VALUES(:org,:account,:id,:source,'COMPENSATION',1,:digest,:offer,:day,300,100,0,30,170,
                        true,true,:publication,:publication,:revision)
                      """)
                  .param("org", scope.organizationId())
                  .param("account", scope.accountId())
                  .param("id", legacyEvent)
                  .param("source", sourceId)
                  .param("digest", publication.digest())
                  .param("offer", offer)
                  .param("day", MONTH.plusDays(4))
                  .param("publication", legacy)
                  .param("revision", revision)
                  .update();
              return revision;
            });
    when(source.coverageEvidence(scope, YearMonth.from(MONTH)))
        .thenReturn(new FinancialSourceService.CoverageEvidence(Map.of(), java.util.Set.of()));
    var legacySummary =
        transactions.run(
            scope,
            () ->
                new MonthlyReportService(jdbc, authorization, source)
                    .summary(scope, YearMonth.from(MONTH)));
    assertEquals("UNKNOWN", legacySummary.breakdown().getFirst().certainty());
    assertEquals(0, legacySummary.coverage().preliminaryEvents());
    var legacyRow =
        transactions.run(
            scope,
            () ->
                economics
                    .financialPage(scope, 0, 50, MONTH, MONTH.plusMonths(1), offer, "COMPENSATION")
                    .items()
                    .getFirst());
    assertFalse(legacyRow.complete());
    assertEquals("UNKNOWN", legacyRow.certainty());
    assertEquals("UNKNOWN", legacyRow.status());
    finishLedger(scope, ledger(source), publication.id());
    transactions.run(
        scope,
        () -> {
          assertEquals(basisRevision, new AccountingBasisService(jdbc, outbox).current(scope));
          jdbc.sql(
                  """
                  SELECT e.cost,e.profit,e.complete,p.recognition_version FROM economics_financial_event e
                  JOIN economics_ledger_publication p ON (p.organization_id,p.account_id,p.id)=
                    (e.organization_id,e.account_id,e.preparation_id)
                  WHERE e.current_revision AND e.source_id=:source
                  """)
              .param("source", sourceId)
              .query(
                  (rs, row) -> {
                    assertNull(rs.getBigDecimal(1));
                    assertNull(rs.getBigDecimal(2));
                    assertFalse(rs.getBoolean(3));
                    assertEquals(LedgerService.RECOGNITION_VERSION, rs.getInt(4));
                    return true;
                  })
              .single();
          jdbc.sql("SELECT cost,current_revision FROM economics_financial_event WHERE id=:id")
              .param("id", legacyEvent)
              .query(
                  (rs, row) -> {
                    assertMoney("100", rs.getBigDecimal(1));
                    assertFalse(rs.getBoolean(2));
                    return true;
                  })
              .single();
          return null;
        });
  }

  @Test
  void compensationMoneyAndReversalsRecognizeOnePhysicalMovementAndPreserveUnknownProof() {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var source = mock(FinancialSourceService.class);
    var ledger = ledger(source);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("100"), BigDecimal.ZERO, MONTH, null, 0));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(new BigDecimal("0.1"), MONTH, null, 0));
        });
    UUID movement = UUID.randomUUID();
    var first = returnPublication(scope, source, "compensation-one", GENERATED, "a");
    supplyReturnFacts(
        scope,
        source,
        first,
        List.of(
            compensationFact(
                first,
                new UUID(0, 10),
                offer,
                movement,
                "300",
                MONTH.plusDays(4),
                "COMPENSATION")));
    finishLedger(scope, ledger, first.id());
    assertConsumption(scope, "300", "100", "30", "170", 1);
    var second =
        returnPublication(scope, source, "compensation-two", GENERATED.plusSeconds(1), "b");
    supplyReturnFacts(
        scope,
        source,
        second,
        List.of(
            compensationFact(
                second,
                new UUID(0, 1),
                offer,
                movement,
                "100",
                MONTH.plusDays(5),
                "COMPENSATION")));
    finishLedger(scope, ledger, second.id());
    assertConsumption(scope, "400", "100", "40", "260", 2);
    var reverse =
        returnPublication(scope, source, "compensation-reversal", GENERATED.plusSeconds(2), "c");
    supplyReturnFacts(
        scope,
        source,
        reverse,
        List.of(
            compensationFact(
                reverse,
                new UUID(0, 20),
                offer,
                movement,
                "-50",
                MONTH.plusDays(6),
                "COMPENSATION_REVERSAL")));
    finishLedger(scope, ledger, reverse.id());
    finishLedger(scope, ledger, reverse.id());
    assertConsumption(scope, "350", "100", "35", "215", 3);

    var withdrawn =
        returnPublication(scope, source, "compensation-two", GENERATED.plusSeconds(3), "d");
    supplyReturnFacts(scope, source, withdrawn, List.of());
    finishLedger(scope, ledger, withdrawn.id());
    assertConsumption(scope, "250", "100", "25", "125", 2);

    var unknown =
        returnPublication(
            scope, source, "compensation-without-proof", GENERATED.plusSeconds(4), "e");
    UUID unknownId = UUID.randomUUID();
    supplyReturnFacts(
        scope,
        source,
        unknown,
        List.of(
            compensationFact(
                unknown, unknownId, offer, null, "20", MONTH.plusDays(7), "COMPENSATION")));
    finishLedger(scope, ledger, unknown.id());
    var unknownAmount =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        SELECT cost,profit,complete FROM economics_financial_event WHERE current_revision AND source_id=:id
                        """)
                    .param("id", unknownId)
                    .query(
                        (rs, row) -> {
                          assertNull(rs.getBigDecimal(1));
                          assertNull(rs.getBigDecimal(2));
                          return rs.getBoolean(3);
                        })
                    .single());
    assertFalse(unknownAmount);
    when(source.coverageEvidence(scope, YearMonth.from(MONTH)))
        .thenReturn(new FinancialSourceService.CoverageEvidence(Map.of(), java.util.Set.of()));
    var reports = new MonthlyReportService(jdbc, authorization, source);
    var summary = transactions.run(scope, () -> reports.summary(scope, YearMonth.from(MONTH)));
    assertMoney("270", summary.totals().income());
    assertNull(summary.totals().cost());
    assertNull(summary.totals().profit());
    assertEquals("INCOMPLETE", summary.coverage().status());
    assertEquals(0, summary.coverage().preliminaryEvents());
    assertEquals(1, summary.breakdown().size());
    assertEquals("CONFIRMED", summary.breakdown().getFirst().certainty());
    assertMoney("270", summary.breakdown().getFirst().totals().income());
    var query =
        new ru.oritas.repricer.platform.TableExportSource.Query(
            "", Map.of("month", YearMonth.from(MONTH).toString()), "", 0, 50);
    var exported =
        transactions.run(
            scope, () -> reports.monthlyPnlExport().projection(scope, query, List.of()));
    var cells =
        transactions
            .run(
                scope,
                () ->
                    jdbc.sql(exported.sql())
                        .params(exported.parameters())
                        .query(
                            (rs, row) ->
                                java.util.Arrays.asList(
                                    new JsonCodec().decode(rs.getString("cells"), String[].class)))
                        .list())
            .stream()
            .filter(row -> unknownId.toString().equals(row.get(5)))
            .findFirst()
            .orElseThrow();
    assertEquals("CONFIRMED", cells.get(8));
    assertMoney("20", new BigDecimal(cells.get(10)));
    assertNull(cells.get(11));
    assertEquals("INCOMPLETE", cells.get(15));
    var incompleteRows =
        transactions.run(
            scope,
            () ->
                economics.financialPage(
                    scope,
                    0,
                    50,
                    MONTH,
                    MONTH.plusMonths(1),
                    offer,
                    "COMPENSATION",
                    "",
                    "INCOMPLETE",
                    "status",
                    "asc"));
    assertEquals(1, incompleteRows.total());
    var incompleteRow = incompleteRows.items().getFirst();
    assertFalse(incompleteRow.complete());
    assertEquals("CONFIRMED", incompleteRow.certainty());
    assertEquals("INCOMPLETE", incompleteRow.status());
  }

  private static FinancialFact compensationFact(
      Publication publication,
      UUID id,
      UUID offer,
      UUID movement,
      String amount,
      LocalDate moneyDate,
      String kind) {
    var proof =
        movement == null
            ? null
            : new FinancialSourceService.InventoryConsumptionEvidence(
                movement,
                FinancialSourceService.ConsumptionKind.LOSS_BEFORE_DELIVERY,
                offer,
                BigDecimal.ONE,
                MONTH.plusDays(2),
                publication.rawFileId());
    var settlement =
        new FinancialSourceService.SettlementEvidence(
            null,
            BigDecimal.ONE,
            new BigDecimal(amount),
            new BigDecimal(amount),
            null,
            null,
            null,
            null,
            FinancialSourceService.InventoryDisposition.CONSUMED,
            null,
            new FinancialSourceService.CostBasisEvidence(
                MONTH, FinancialSourceService.CostBasisKind.ISSUE_DATE, publication.rawFileId()),
            proof);
    return new FinancialFact(
        1,
        id,
        1,
        publication.digest(),
        offer,
        moneyDate,
        kind,
        new BigDecimal(amount),
        null,
        BigDecimal.ONE,
        null,
        publication.rawFileId(),
        settlement,
        null);
  }

  private static void assertConsumption(
      Scope scope, String income, String cost, String tax, String profit, long monetaryEvents) {
    transactions.run(
        scope,
        () -> {
          jdbc.sql(
                  """
                  SELECT sum(income),sum(cost),sum(tax),sum(profit),count(*) FILTER(WHERE component='INVENTORY_CONSUMPTION'),
                    count(*) FILTER(WHERE component IN ('COMPENSATION','COMPENSATION_REVERSAL')),
                    min(accounting_date) FILTER(WHERE component='INVENTORY_CONSUMPTION')
                  FROM economics_financial_event WHERE current_revision
                  """)
              .query(
                  (rs, row) -> {
                    assertMoney(income, rs.getBigDecimal(1));
                    assertMoney(cost, rs.getBigDecimal(2));
                    assertMoney(tax, rs.getBigDecimal(3));
                    assertMoney(profit, rs.getBigDecimal(4));
                    assertEquals(1, rs.getLong(5));
                    assertEquals(monetaryEvents, rs.getLong(6));
                    assertEquals(MONTH.plusDays(2), rs.getObject(7, LocalDate.class));
                    return true;
                  })
              .single();
          return null;
        });
  }

  @Test
  void lateCompensationUsesOnlyProvenOriginalCostDateAndKeepsUnknownCostUnknown() {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var source = mock(FinancialSourceService.class);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("100"), BigDecimal.ZERO, MONTH, MONTH.plusMonths(1), 0));
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("900"), BigDecimal.ZERO, MONTH.plusMonths(1), null, 1));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(new BigDecimal("0.1"), MONTH, null, 0));
        });
    var publication = returnPublication(scope, source, "late-compensation", GENERATED, "3");
    var knownId = UUID.randomUUID();
    var unknownId = UUID.randomUUID();
    var facts = new ArrayList<FinancialFact>();
    for (int index = 0; index < 2; index++) {
      var proof =
          index == 0
              ? new FinancialSourceService.CostBasisEvidence(
                  MONTH, FinancialSourceService.CostBasisKind.ISSUE_DATE, publication.rawFileId())
              : null;
      var settlement =
          new FinancialSourceService.SettlementEvidence(
              null,
              BigDecimal.ONE,
              new BigDecimal("300"),
              new BigDecimal("300"),
              null,
              null,
              null,
              null,
              FinancialSourceService.InventoryDisposition.CONSUMED,
              null,
              proof,
              index == 0
                  ? new FinancialSourceService.InventoryConsumptionEvidence(
                      UUID.randomUUID(),
                      FinancialSourceService.ConsumptionKind.LOSS_BEFORE_DELIVERY,
                      offer,
                      BigDecimal.ONE,
                      MONTH,
                      publication.rawFileId())
                  : null);
      facts.add(
          new FinancialFact(
              index + 1,
              index == 0 ? knownId : unknownId,
              1,
              publication.digest(),
              offer,
              MONTH.plusMonths(1),
              "COMPENSATION",
              new BigDecimal("300"),
              null,
              BigDecimal.ONE,
              null,
              publication.rawFileId(),
              settlement,
              null));
    }
    supplyReturnFacts(scope, source, publication, facts);
    finishLedger(scope, ledger(source), publication.id());
    var known =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        SELECT sum(e.cost),sum(e.profit),f.fact->'settlement'->'costBasis'->>'kind'
                        FROM economics_financial_event e JOIN economics_source_fact f
                          ON (f.organization_id,f.account_id,f.publication_id,f.source_id)=
                            (e.organization_id,e.account_id,e.publication_id,e.source_id)
                        WHERE e.current_revision AND e.source_id=:id
                        GROUP BY f.fact->'settlement'->'costBasis'->>'kind'
                        """)
                    .param("id", knownId)
                    .query(
                        (rs, row) ->
                            new CompensationAmounts(
                                rs.getBigDecimal(1), rs.getBigDecimal(2), rs.getString(3)))
                    .single());
    assertMoney("100", known.cost());
    assertMoney("170", known.profit());
    assertEquals("ISSUE_DATE", known.kind());
    var unknown =
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        """
                        SELECT cost,profit,complete,income FROM economics_financial_event
                        WHERE current_revision AND source_id=:id
                        """)
                    .param("id", unknownId)
                    .query(
                        (rs, row) -> {
                          assertNull(rs.getBigDecimal(1));
                          assertNull(rs.getBigDecimal(2));
                          assertFalse(rs.getBoolean(3));
                          return rs.getBigDecimal(4);
                        })
                    .single());
    assertMoney("300", unknown);
  }

  private record CompensationAmounts(BigDecimal cost, BigDecimal profit, String kind) {}

  private static BigDecimal revenueProfit(Scope scope) {
    return transactions.run(
        scope,
        () ->
            jdbc.sql(
                    "SELECT profit FROM economics_financial_event WHERE current_revision AND"
                        + " component='REVENUE'")
                .query(BigDecimal.class)
                .single());
  }

  @BeforeAll
  static void prepare() throws Exception {
    POSTGRES.start();
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        var statement = connection.createStatement()) {
      statement.execute(
          "CREATE ROLE repricer_migrator LOGIN BYPASSRLS PASSWORD 'migration-test-only'");
      statement.execute("CREATE ROLE repricer_api LOGIN NOBYPASSRLS PASSWORD 'api-test-only'");
      statement.execute(
          "CREATE ROLE repricer_worker LOGIN NOBYPASSRLS PASSWORD 'worker-test-only'");
      statement.execute("GRANT ALL ON SCHEMA public TO repricer_migrator");
    }
    var connection =
        DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), "repricer_migrator", "migration-test-only");
    var database =
        DatabaseFactory.getInstance()
            .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    try (Liquibase liquibase =
        new Liquibase("db/changelog/master.xml", new ClassLoaderResourceAccessor(), database)) {
      liquibase.update(new liquibase.Contexts());
    }
    var source =
        new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "repricer_worker", "worker-test-only");
    jdbc = JdbcClient.create(source);
    authorization = new AuthorizationService(jdbc);
    transactions =
        new ScopeTransactionRunner(jdbc, new DataSourceTransactionManager(source), authorization);
    var json = new JsonCodec();
    var beans = new DefaultListableBeanFactory();
    outbox =
        new OutboxService(
            jdbc, json, mock(JobRuntime.class), beans.getBeanProvider(OutboxRecipient.class));
    var access =
        new AccessService(
            jdbc,
            transactions,
            authorization,
            new IdempotencyService(jdbc, json),
            new AuditService(jdbc),
            outbox,
            new IdentityService(jdbc));
    admin = IdentityService.userId("https://ledger.test.invalid/realm", "admin");
    organization = access.initManagedUsers("https://ledger.test.invalid/realm", "admin", "test");
  }

  @AfterAll
  static void stop() {
    POSTGRES.stop();
  }

  @Test
  void incompletePreparationIsInvisibleAndNewEditionReplacesWholeCohortOnce() {
    Scope scope = scope();
    var source = mock(FinancialSourceService.class);
    var ledger = ledger(source);
    Publication first =
        publication(scope, source, GENERATED, "a", List.of(new BigDecimal("100")), true);
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, first.id())));
    assertMoney("100", currentExpense(scope));

    List<BigDecimal> corrected = new ArrayList<>();
    for (int index = 0; index < 200; index++) {
      corrected.add(BigDecimal.ONE);
    }
    corrected.add(new BigDecimal("-7.125"));
    Publication second =
        publication(scope, source, GENERATED.plusSeconds(60), "b", corrected, true);
    assertFalse(transactions.run(scope, () -> ledger.prepareNext(scope, second.id())));
    assertMoney("100", currentExpense(scope));
    assertEquals("PREPARING", state(scope, second.id()));
    finishLedger(scope, ledger, second.id());
    assertMoney("192.875", currentExpense(scope));
    assertEquals("SUPERSEDED", state(scope, first.id()));
    assertEquals("PUBLISHED", state(scope, second.id()));
    finishLedger(scope, ledger, second.id());
    assertMoney("192.875", currentExpense(scope));
    assertEquals(
        202L,
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT count(*) FROM economics_financial_event")
                    .query(Long.class)
                    .single()));
    assertEquals(
        201L,
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT count(*) FROM economics_financial_event WHERE current_revision")
                    .query(Long.class)
                    .single()));
    assertMoney(
        "-192.875",
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT sum(profit) FROM economics_financial_event WHERE current_revision")
                    .query(BigDecimal.class)
                    .single()));
    assertMoney(
        "0",
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT sum(income) FROM economics_financial_event WHERE current_revision")
                    .query(BigDecimal.class)
                    .single()));
    assertEquals(
        0L,
        transactions.run(
            scope,
            () ->
                jdbc.sql(
                        "SELECT count(*) FROM economics_financial_event WHERE recognized_units IS"
                            + " NOT NULL")
                    .query(Long.class)
                    .single()));
    when(source.coverageEvidence(scope, YearMonth.from(MONTH)))
        .thenReturn(
            new FinancialSourceService.CoverageEvidence(
                Map.of("SERVICE", true), java.util.Set.of(second.id())));
    var report = new MonthlyReportService(jdbc, authorization, source);
    var coverage = transactions.run(scope, () -> report.coverage(scope, YearMonth.from(MONTH)));
    assertEquals("INCOMPLETE", coverage.status());
    assertEquals(201, coverage.unallocatedEvents());
  }

  @Test
  void olderIncompleteAndConflictingSourcesCannotReplaceCurrentEdition() {
    Scope scope = scope();
    var source = mock(FinancialSourceService.class);
    var ledger = ledger(source);
    Publication current =
        publication(scope, source, GENERATED, "c", List.of(new BigDecimal("42")), true);
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, current.id())));
    Publication older =
        publication(scope, source, GENERATED.minusSeconds(10), "d", List.of(BigDecimal.ONE), true);
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, older.id())));
    assertEquals("OBSOLETE", state(scope, older.id()));
    assertMoney("42", currentExpense(scope));
    Publication conflict =
        publication(scope, source, GENERATED, "e", List.of(BigDecimal.TEN), true);
    BusinessException ambiguity =
        assertThrows(
            BusinessException.class,
            () -> transactions.run(scope, () -> ledger.prepareNext(scope, conflict.id())));
    assertEquals("SERVICE_EDITION_CONFLICT", ambiguity.code());
    assertMoney("42", currentExpense(scope));
    Publication partial =
        publication(scope, source, GENERATED.plusSeconds(10), "f", List.of(BigDecimal.TEN), false);
    BusinessException incomplete =
        assertThrows(
            BusinessException.class,
            () -> transactions.run(scope, () -> ledger.prepareNext(scope, partial.id())));
    assertEquals("SERVICE_COHORT_INCOMPLETE", incomplete.code());
    assertMoney("42", currentExpense(scope));
  }

  @Test
  void invalidLastRowRollsBackBatchAndKeepsPublishedCohort() {
    Scope scope = scope();
    var source = mock(FinancialSourceService.class);
    var ledger = ledger(source);
    Publication current =
        publication(scope, source, GENERATED, "1", List.of(new BigDecimal("17")), true);
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, current.id())));
    Publication invalid =
        publication(
            scope,
            source,
            GENERATED.plusSeconds(20),
            "2",
            List.of(BigDecimal.ONE, BigDecimal.TEN),
            true);
    FinancialFact first = fact(invalid, 1, BigDecimal.ONE, MONTH);
    FinancialFact wrongPeriod = fact(invalid, 2, BigDecimal.TEN, MONTH.plusMonths(1));
    when(source.page(scope, invalid.id(), 0, 200)).thenReturn(List.of(first, wrongPeriod));
    BusinessException failure =
        assertThrows(
            BusinessException.class,
            () -> transactions.run(scope, () -> ledger.prepareNext(scope, invalid.id())));
    assertEquals("SERVICE_COHORT_CONFLICT", failure.code());
    assertMoney("17", currentExpense(scope));
    assertEquals(
        0L,
        transactions.run(
            scope,
            () ->
                jdbc.sql("SELECT count(*) FROM economics_financial_event WHERE publication_id=:id")
                    .param("id", invalid.id())
                    .query(Long.class)
                    .single()));
  }

  private static LedgerService ledger(FinancialSourceService source) {
    return new LedgerService(
        jdbc,
        source,
        new AccountingService(jdbc),
        outbox,
        mock(StoredFileService.class),
        new AccountingBasisService(jdbc, outbox));
  }

  private static Publication publication(
      Scope scope,
      FinancialSourceService source,
      Instant generated,
      String digestCharacter,
      List<BigDecimal> amounts,
      boolean complete) {
    UUID id = UUID.randomUUID();
    UUID raw = UUID.randomUUID();
    String digest = digestCharacter.repeat(64);
    transactions.run(
        scope,
        () ->
            jdbc.sql(
                    """
                    INSERT INTO platform_file(id,organization_id,account_id,subject_id,kind,object_key,
                      media_type,state,byte_count,sha256,eof_confirmed)
                    VALUES (:id,:org,:account,:subject,'MARKETPLACE_RAW',:key,'application/zip','STORED',100,:digest,true)
                    """)
                .param("id", raw)
                .param("org", scope.organizationId())
                .param("account", scope.accountId())
                .param("subject", scope.subjectId())
                .param("key", "ledger-test/" + raw)
                .param("digest", digest)
                .update());
    var publication =
        new Publication(
            id,
            MONTH,
            MONTH.plusMonths(1),
            generated,
            amounts.size(),
            Map.of("SERVICE", complete, "REVENUE", false),
            digest,
            raw,
            "SERVICES:SERVICE_ACCRUAL_DATE:" + YearMonth.from(MONTH));
    when(source.publication(scope, id)).thenReturn(publication);
    List<FinancialFact> facts = new ArrayList<>();
    for (int index = 0; index < amounts.size(); index++) {
      facts.add(fact(publication, index + 1, amounts.get(index), MONTH));
    }
    when(source.page(eq(scope), eq(id), anyLong(), anyInt()))
        .thenAnswer(
            invocation -> {
              long after = invocation.getArgument(2, Long.class);
              int limit = invocation.getArgument(3, Integer.class);
              return facts.stream().filter(fact -> fact.rowNumber() > after).limit(limit).toList();
            });
    return publication;
  }

  private static FinancialFact fact(
      Publication publication, long ordinal, BigDecimal amount, LocalDate day) {
    UUID id =
        UUID.nameUUIDFromBytes((publication.id() + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
    return new FinancialFact(
        ordinal,
        id,
        1,
        publication.digest(),
        null,
        day,
        "SERVICE",
        amount,
        "STORAGE",
        null,
        null,
        publication.rawFileId(),
        null,
        null);
  }

  @Test
  void monthlyCoverageChecksLateCompensationOutsideItsSourcePublicationPeriod() {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var source = mock(FinancialSourceService.class);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("100"), BigDecimal.ZERO, MONTH, null, 0));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(new BigDecimal("0.1"), MONTH, null, 0));
        });
    var original = returnPublication(scope, source, "late-monthly-compensation", GENERATED, "4");
    LocalDate nextMonth = MONTH.plusMonths(1);
    supplyReturnFacts(
        scope,
        source,
        original,
        List.of(
            compensationFact(
                original,
                UUID.randomUUID(),
                offer,
                UUID.randomUUID(),
                "300",
                nextMonth.plusDays(2),
                "COMPENSATION")));
    var ledger = ledger(source);
    finishLedger(scope, ledger, original.id());
    var seed = publication(scope, source, GENERATED.plusSeconds(1), "5", List.of(), true);
    Map<String, Boolean> coverage =
        Map.of("REVENUE", true, "REFUND", true, "SERVICE", true, "COMPENSATION", true);
    var currentMonth =
        new Publication(
            seed.id(),
            nextMonth,
            nextMonth.plusMonths(1),
            seed.generatedAt(),
            0,
            coverage,
            seed.digest(),
            seed.rawFileId(),
            "REALIZATION:empty-current-month");
    supplyReturnFacts(scope, source, currentMonth, List.of());
    finishLedger(scope, ledger, currentMonth.id());
    finishLedger(scope, ledger, original.id());
    finishLedger(scope, ledger, currentMonth.id());
    when(source.coverageEvidence(scope, YearMonth.from(nextMonth)))
        .thenReturn(
            new FinancialSourceService.CoverageEvidence(
                coverage, java.util.Set.of(currentMonth.id())));
    var reports = new MonthlyReportService(jdbc, authorization, source);
    var before = transactions.run(scope, () -> reports.summary(scope, YearMonth.from(nextMonth)));
    assertEquals("COMPLETE", before.coverage().status());
    assertMoney("300", before.totals().income());

    transactions.run(
        scope,
        () ->
            economics.publishTax(
                scope, new EconomicsService.TaxInput(new BigDecimal("0.2"), MONTH, null, 1)));
    // The month-overlapping empty publication is current; the late monetary fact still is not.
    finishLedger(scope, ledger, currentMonth.id());
    var after = transactions.run(scope, () -> reports.summary(scope, YearMonth.from(nextMonth)));
    assertEquals("ACCOUNTING_RECALCULATION_PENDING", after.coverage().reason());
    assertEquals("INCOMPLETE", after.coverage().status());
    assertMoney("300", after.totals().income());
    assertFalse(after.daily().get(2).complete());

    finishLedger(scope, ledger, original.id());
    assertEquals(
        "COMPLETE",
        transactions.run(scope, () -> reports.coverage(scope, YearMonth.from(nextMonth))).status());
  }

  @Test
  void monthlySummaryAndUnitsIncludeServicesWithoutAssigningUnallocatedCostsOrPayments() {
    Scope scope = scope();
    UUID offer = monthlyOffer(scope);
    var economics =
        new EconomicsService(
            jdbc,
            authorization,
            new AuditService(jdbc),
            outbox,
            new AccountingBasisService(jdbc, outbox));
    transactions.run(
        scope,
        () -> {
          economics.publishCost(
              scope,
              new EconomicsService.CostInput(
                  offer, new BigDecimal("20"), new BigDecimal("2"), MONTH, null, 0));
          economics.publishTax(
              scope, new EconomicsService.TaxInput(new BigDecimal("0.1"), MONTH, null, 0));
        });
    var source = mock(FinancialSourceService.class);
    var seed = publication(scope, source, GENERATED, "b", List.of(BigDecimal.ONE), true);
    var publication =
        new Publication(
            seed.id(),
            MONTH,
            MONTH.plusMonths(1),
            GENERATED,
            4,
            Map.of("REVENUE", true, "REFUND", true, "SERVICE", true, "COMPENSATION", true),
            seed.digest(),
            seed.rawFileId(),
            "REALIZATION:monthly:" + YearMonth.from(MONTH));
    var sale =
        new FinancialFact(
            1,
            UUID.randomUUID(),
            1,
            seed.digest(),
            offer,
            MONTH,
            "REVENUE",
            new BigDecimal("200"),
            null,
            new BigDecimal("2"),
            null,
            seed.rawFileId(),
            new FinancialSourceService.SettlementEvidence(
                UUID.randomUUID(),
                new BigDecimal("2"),
                new BigDecimal("200"),
                new BigDecimal("200"),
                null,
                null,
                null,
                null,
                FinancialSourceService.InventoryDisposition.CONSUMED,
                null,
                new FinancialSourceService.CostBasisEvidence(
                    MONTH,
                    FinancialSourceService.CostBasisKind.DELIVERY_FALLBACK,
                    seed.rawFileId()),
                null),
            null);
    var service =
        new FinancialFact(
            2,
            UUID.randomUUID(),
            1,
            seed.digest(),
            offer,
            MONTH,
            "SERVICE",
            new BigDecimal("30"),
            "DELIVERY",
            null,
            null,
            seed.rawFileId(),
            null,
            null);
    var unallocated =
        new FinancialFact(
            3,
            UUID.randomUUID(),
            1,
            seed.digest(),
            null,
            MONTH,
            "SERVICE",
            new BigDecimal("10"),
            "STORAGE",
            null,
            null,
            seed.rawFileId(),
            null,
            null);
    var payment =
        new FinancialFact(
            4,
            UUID.randomUUID(),
            1,
            seed.digest(),
            null,
            MONTH,
            "PAYMENT",
            new BigDecimal("1000"),
            null,
            null,
            null,
            seed.rawFileId(),
            null,
            null);
    var facts = List.of(sale, service, unallocated, payment);
    when(source.publication(scope, seed.id())).thenReturn(publication);
    when(source.page(eq(scope), eq(seed.id()), anyLong(), anyInt()))
        .thenAnswer(
            invocation -> {
              long after = invocation.getArgument(2, Long.class);
              int limit = invocation.getArgument(3, Integer.class);
              return facts.stream()
                  .filter(value -> value.rowNumber() > after)
                  .limit(limit)
                  .toList();
            });
    when(source.coverageEvidence(scope, YearMonth.from(MONTH)))
        .thenReturn(
            new FinancialSourceService.CoverageEvidence(
                publication.completeByComponent(), java.util.Set.of(publication.id())));
    var ledger = ledger(source);
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, seed.id())));
    var monthly = new MonthlyReportService(jdbc, authorization, source);
    assertEquals(
        "ACCOUNTING_RECALCULATION_PENDING",
        transactions.run(scope, () -> monthly.coverage(scope, YearMonth.from(MONTH))).reason());
    assertTrue(transactions.run(scope, () -> ledger.prepareNext(scope, seed.id())));
    var summary = transactions.run(scope, () -> monthly.summary(scope, YearMonth.from(MONTH)));
    assertEquals("COMPLETE", summary.coverage().status());
    assertEquals(3, summary.coverage().recognizedEvents());
    assertEquals(1, summary.coverage().unallocatedEvents());
    assertMoney("200", summary.totals().income());
    assertMoney("96", summary.totals().profit());
    assertEquals(YearMonth.from(MONTH).lengthOfMonth(), summary.daily().size());
    assertEquals(MONTH, summary.daily().getFirst().date());
    assertMoney("200", summary.daily().getFirst().income());
    assertEquals(3, summary.daily().getFirst().events());
    assertTrue(summary.daily().getFirst().complete());
    assertEquals(MONTH.plusDays(1), summary.daily().get(1).date());
    assertMoney("0", summary.daily().get(1).income());
    assertEquals(0, summary.daily().get(1).events());
    assertTrue(summary.daily().get(1).complete());
    assertMoney(
        "-10",
        summary.breakdown().stream()
            .filter(value -> value.allocation().equals("UNALLOCATED"))
            .findFirst()
            .orElseThrow()
            .totals()
            .profit());
    var exported = monthly.monthlyUnitEconomicsExport();
    var query =
        new ru.oritas.repricer.platform.TableExportSource.Query(
            "", Map.of("month", "2026-09"), "", 0, 50);
    var projection = transactions.run(scope, () -> exported.projection(scope, query, List.of()));
    var captured =
        transactions.run(
            scope,
            () ->
                jdbc.sql(projection.sql())
                    .params(projection.parameters())
                    .query(
                        (rs, index) ->
                            java.util.Arrays.asList(
                                new JsonCodec().decode(rs.getString("cells"), String[].class)))
                    .list());
    assertEquals(2, captured.size());
    var product =
        captured.stream()
            .filter(cells -> offer.toString().equals(cells.get(1)))
            .findFirst()
            .orElseThrow();
    var rendered = exported.exportCells(projection.columns(), product);
    assertMoney("106", new BigDecimal(rendered.get(8)));
    assertMoney("53", new BigDecimal(rendered.get(11)));
    var other = captured.stream().filter(cells -> cells.get(1) == null).findFirst().orElseThrow();
    assertEquals(null, exported.exportCells(projection.columns(), other).get(11));
    assertMoney("-10", new BigDecimal(other.get(8)));
    var halfEven = new ArrayList<>(product);
    halfEven.set(8, "0.000000000000000001");
    assertMoney("0", new BigDecimal(exported.exportCells(projection.columns(), halfEven).get(11)));
    transactions.run(
        scope,
        () ->
            economics.publishCost(
                scope,
                new EconomicsService.CostInput(
                    offer, new BigDecimal("25"), new BigDecimal("2"), MONTH, null, 1)));
    var pending = transactions.run(scope, () -> monthly.summary(scope, YearMonth.from(MONTH)));
    assertEquals("ACCOUNTING_RECALCULATION_PENDING", pending.coverage().reason());
    assertMoney("200", pending.daily().getFirst().income());
    assertFalse(pending.daily().getFirst().complete());
    assertEquals(null, pending.daily().get(1).income());
    assertFalse(pending.daily().get(1).complete());
    assertMoney("53", new BigDecimal(exported.exportCells(projection.columns(), product).get(11)));
    Scope outsider = new Scope(scope.organizationId(), scope.accountId(), UUID.randomUUID());
    assertThrows(
        BusinessException.class,
        () -> transactions.run(outsider, () -> monthly.summary(outsider, YearMonth.from(MONTH))));
    assertThrows(
        BusinessException.class,
        () -> transactions.run(outsider, () -> exported.projection(outsider, query, List.of())));
  }

  private static UUID monthlyOffer(Scope scope) {
    UUID offer = UUID.randomUUID();
    transactions.run(
        scope,
        () -> {
          UUID catalog = UUID.randomUUID();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_sync_run(id,organization_id,account_id,source_type,state)
                  VALUES(:id,:org,:account,'CATALOG','PUBLISHED')
                  """)
              .param("id", catalog)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .update();
          jdbc.sql(
                  """
                  INSERT INTO marketplace_offer(id,organization_id,account_id,external_id,sku,name,
                    observed_at,publication_id)
                  VALUES(:id,:org,:account,:external,:external,'Monthly offer',clock_timestamp(),:catalog)
                  """)
              .param("id", offer)
              .param("org", scope.organizationId())
              .param("account", scope.accountId())
              .param("external", offer.toString())
              .param("catalog", catalog)
              .update();
        });
    return offer;
  }

  private static Scope scope() {
    UUID account = UUID.randomUUID();
    transactions.run(
        new Scope(organization, null, admin),
        () ->
            jdbc.sql(
                    """
                    INSERT INTO marketplace_account(id,organization_id,marketplace,external_id,name,timezone)
                    VALUES (:id,:org,'YANDEX',:external,'Ledger test','Europe/Moscow')
                    """)
                .param("id", account)
                .param("org", organization)
                .param("external", account.toString())
                .update());
    return new Scope(organization, account, admin);
  }

  private static BigDecimal currentExpense(Scope scope) {
    return transactions.run(
        scope,
        () ->
            jdbc.sql(
                    "SELECT coalesce(sum(expenses),0) FROM economics_financial_event WHERE"
                        + " current_revision")
                .query(BigDecimal.class)
                .single());
  }

  private static String state(Scope scope, UUID publication) {
    return transactions.run(
        scope,
        () ->
            jdbc.sql(
                    "SELECT state FROM economics_ledger_publication WHERE source_publication_id=:id"
                        + " ORDER BY accounting_revision DESC LIMIT 1")
                .param("id", publication)
                .query(String.class)
                .single());
  }

  private static void assertMoney(String expected, BigDecimal actual) {
    assertEquals(0, new BigDecimal(expected).compareTo(actual));
  }
}
