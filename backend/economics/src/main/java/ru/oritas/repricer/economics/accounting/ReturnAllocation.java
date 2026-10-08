package ru.oritas.repricer.economics.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.oritas.repricer.economics.calculation.EconomicCalculator;
import ru.oritas.repricer.marketplace.FinancialSourceService.FinancialFact;
import ru.oritas.repricer.platform.BusinessException;

/** One original line's independent monetary and inventory movements, in stable source order. */
final class ReturnAllocation {
  record Original(FinancialFact fact, EconomicCalculator.Result amounts) {}

  private final Allocation allocation = new Allocation();
  private final EconomicCalculator calculator = new EconomicCalculator();

  /** Unknown components do not erase the known lower bound or prove the last returned part. */
  private record ReturnTotal(BigDecimal known, boolean complete) {
    private static final ReturnTotal EMPTY = new ReturnTotal(BigDecimal.ZERO, true);

    ReturnTotal include(BigDecimal amount) {
      return amount == null
          ? new ReturnTotal(known, false)
          : new ReturnTotal(EconomicCalculator.checked(known.add(amount)), complete);
    }

    boolean fullyCovers(BigDecimal original) {
      return complete && original != null && known.compareTo(original) == 0;
    }
  }

  Map<UUID, EconomicCalculator.Result> recognize(
      List<Original> originals, List<FinancialFact> returned) {
    BigDecimal quantity = originals.isEmpty() ? null : BigDecimal.ZERO;
    BigDecimal cost = quantity;
    BigDecimal revenue = quantity;
    BigDecimal taxBase = quantity;
    BigDecimal tax = quantity;
    UUID offerId = null;
    for (var original : originals) {
      var source = original.fact().settlement();
      if (source != null && source.units() != null && source.units().signum() <= 0) {
        throw conflict("Количество исходной части продажи должно быть положительным");
      }
      quantity = add(quantity, source == null ? null : source.units());
      cost = add(cost, original.amounts().costConsumed());
      revenue = add(revenue, original.amounts().sellerRevenue());
      taxBase = add(taxBase, source == null ? null : source.taxBase());
      tax = add(tax, original.amounts().tax());
      if (offerId != null && !offerId.equals(original.fact().offerId())) {
        throw conflict("Исходная строка связана с разными товарами");
      }
      offerId = original.fact().offerId();
    }
    if (quantity != null && quantity.signum() <= 0) {
      throw conflict("Количество исходной продажи должно быть положительным");
    }
    var ordered =
        returned.stream()
            .sorted(
                Comparator.comparing(FinancialFact::accountingDate)
                    .thenComparing(FinancialFact::id))
            .toList();
    Map<UUID, FinancialFact> inventoryProofs = new HashMap<>();
    Map<UUID, BigDecimal> quantities = new HashMap<>();
    var refunds = new HashSet<UUID>();
    for (var fact : ordered) {
      var source = fact.settlement();
      if (offerId != null && !offerId.equals(fact.offerId())) {
        throw conflict("Возврат не принадлежит товару исходной продажи");
      }
      if (source == null) {
        continue;
      }
      if (source.units() != null && source.units().signum() <= 0) {
        throw conflict("Количество возврата должно быть положительным");
      }
      if (source.returnId() != null && source.units() != null) {
        BigDecimal previous = quantities.putIfAbsent(source.returnId(), source.units());
        if (previous != null && previous.compareTo(source.units()) != 0) {
          throw conflict("Компоненты одного возврата имеют разное количество");
        }
      }
      if (source.returnId() != null
          && fact.kind().equals("REFUND")
          && !refunds.add(source.returnId())) {
        throw conflict("Для одного возврата указано несколько основных денежных источников");
      }
      if (restored(fact) && source.returnId() != null) {
        FinancialFact previous = inventoryProofs.get(source.returnId());
        if (previous != null && !sameQuantity(previous, fact)) {
          throw conflict("Доказательства одного возврата содержат разное количество");
        }
        // A separate physical event establishes the receipt date. A combined source is used only
        // when no independent complete physical event exists for the same canonical return.
        if (previous == null
            || (previous.kind().equals("REFUND") && fact.kind().equals("RETURN_PHYSICAL"))) {
          inventoryProofs.put(source.returnId(), fact);
        }
      }
    }
    ReturnTotal refundedUnits = ReturnTotal.EMPTY;
    ReturnTotal refundedMoney = ReturnTotal.EMPTY;
    ReturnTotal refundedBase = ReturnTotal.EMPTY;
    BigDecimal reversedTax = BigDecimal.ZERO;
    ReturnTotal restoredUnits = ReturnTotal.EMPTY;
    BigDecimal restoredCost = BigDecimal.ZERO;
    Map<UUID, EconomicCalculator.Result> result = new HashMap<>();
    for (var fact : ordered) {
      var source = fact.settlement();
      List<String> missing = new ArrayList<>();
      BigDecimal income = BigDecimal.ZERO;
      BigDecimal reversal = BigDecimal.ZERO;
      BigDecimal restoration = BigDecimal.ZERO;
      if (fact.kind().equals("REFUND")) {
        if (source == null || !Boolean.TRUE.equals(source.refundConfirmed())) {
          throw new BusinessException(
              "REFUND_UNCONFIRMED", 409, "Физический возврат не подтверждает денежный возврат");
        }
        income = source.sellerRevenue();
        requireNonnegative(income);
        requireNonnegative(source.taxBase());
        refundedMoney = refundedMoney.include(income);
        refundedBase = refundedBase.include(source.taxBase());
        refundedUnits = refundedUnits.include(source.units());
        bounded(refundedMoney.known(), revenue);
        bounded(refundedBase.known(), taxBase);
        bounded(refundedUnits.known(), quantity);
        if (source.returnId() == null) {
          missing.add("RETURN_ID_UNKNOWN");
        }
        if (source.units() == null || quantity == null) {
          missing.add("ORIGINAL_QUANTITY_UNKNOWN");
        }
        if (revenue == null) {
          missing.add("ORIGINAL_REVENUE_UNKNOWN");
        }
        if (source.taxBase() == null || taxBase == null || tax == null) {
          reversal = null;
        } else if (taxBase.signum() == 0 || source.taxBase().signum() == 0) {
          reversal = BigDecimal.ZERO;
        } else {
          boolean last = refundedBase.fullyCovers(taxBase);
          reversal = allocation.returnCost(tax, taxBase, refundedBase.known(), reversedTax, last);
          reversedTax = reversedTax.add(reversal);
        }
      }
      if (restored(fact)) {
        if (source == null || source.returnId() == null) {
          restoration = null;
          missing.add("RETURN_ID_UNKNOWN");
        } else if (inventoryProofs.get(source.returnId()).id().equals(fact.id())) {
          restoredUnits = restoredUnits.include(source.units());
          bounded(restoredUnits.known(), quantity);
          if (cost == null || quantity == null || source.units() == null) {
            restoration = null;
          } else {
            boolean last = restoredUnits.fullyCovers(quantity);
            restoration =
                allocation.returnCost(cost, quantity, restoredUnits.known(), restoredCost, last);
            restoredCost = restoredCost.add(restoration);
          }
        }
      }
      result.put(fact.id(), calculator.returnMovements(income, restoration, reversal, missing));
    }
    return Map.copyOf(result);
  }

  private static boolean restored(FinancialFact fact) {
    var source = fact.settlement();
    return source != null
        && Boolean.TRUE.equals(source.physicalReceiptConfirmed())
        && Boolean.TRUE.equals(source.resalable())
        && Boolean.TRUE.equals(source.stockRestored());
  }

  private static boolean sameQuantity(FinancialFact first, FinancialFact second) {
    var firstUnits = first.settlement().units();
    var secondUnits = second.settlement().units();
    return firstUnits == null
        ? secondUnits == null
        : secondUnits != null && firstUnits.compareTo(secondUnits) == 0;
  }

  private static BigDecimal add(BigDecimal first, BigDecimal second) {
    return first == null || second == null ? null : EconomicCalculator.checked(first.add(second));
  }

  private static void bounded(BigDecimal used, BigDecimal total) {
    if (used != null && total != null && used.compareTo(total) > 0) {
      throw new BusinessException(
          "RETURN_EXCEEDS_ORIGINAL",
          409,
          "Возвраты превышают количество, стоимость или налог исходной продажи");
    }
  }

  private static void requireNonnegative(BigDecimal value) {
    if (value != null && value.signum() < 0) {
      throw conflict("Денежный возврат задаётся положительной величиной");
    }
  }

  private static BusinessException conflict(String message) {
    return new BusinessException("RETURN_SOURCE_CONFLICT", 409, message);
  }
}
