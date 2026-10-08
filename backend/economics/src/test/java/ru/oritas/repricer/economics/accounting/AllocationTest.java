package ru.oritas.repricer.economics.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AllocationTest {
  @Test
  void allocationRetainsRoundingRemainderInStableRecipientOrder() {
    UUID first = new UUID(0, 1);
    UUID second = new UUID(0, 2);
    UUID third = new UUID(0, 3);
    var result =
        new Allocation()
            .allocate(
                new BigDecimal("1.00"),
                List.of(
                    new Allocation.Weight(third, BigDecimal.ONE),
                    new Allocation.Weight(first, BigDecimal.ONE),
                    new Allocation.Weight(second, BigDecimal.ONE)));
    assertThat(result)
        .containsExactly(
            new Allocation.Part(first, new BigDecimal("0.333333333333333333")),
            new Allocation.Part(second, new BigDecimal("0.333333333333333334")),
            new Allocation.Part(third, new BigDecimal("0.333333333333333333")));
  }

  @Test
  void zeroWeightNeverReceivesTheRoundingRemainder() {
    var parts =
        new Allocation()
            .allocate(
                BigDecimal.ONE,
                List.of(
                    new Allocation.Weight(new UUID(0, 1), BigDecimal.ONE),
                    new Allocation.Weight(new UUID(0, 2), BigDecimal.ONE),
                    new Allocation.Weight(new UUID(0, 3), BigDecimal.ONE),
                    new Allocation.Weight(new UUID(0, 4), BigDecimal.ZERO)));
    assertThat(parts.get(2).amount()).isEqualByComparingTo("0.333333333333333333");
    assertThat(parts.get(3).amount()).isEqualByComparingTo("0");
    assertThat(parts.stream().map(Allocation.Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("1");
  }

  @Test
  void cumulativeHalfEvenPreservesMicroAmountsAndNegativeCredits() {
    var allocation = new Allocation();
    var weights =
        java.util.stream.IntStream.rangeClosed(1, 5)
            .mapToObj(index -> new Allocation.Weight(new UUID(0, index), BigDecimal.ONE))
            .toList();
    var tiny = allocation.allocate(new BigDecimal("0.000000000000000003"), weights);
    assertThat(tiny.stream().map(Allocation.Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("0.000000000000000003");
    assertThat(tiny).allSatisfy(part -> assertThat(part.amount()).isNotNegative());
    var ratio =
        List.of(
            new Allocation.Weight(new UUID(0, 1), BigDecimal.ONE),
            new Allocation.Weight(new UUID(0, 2), new BigDecimal("5")));
    var positive = allocation.allocate(BigDecimal.ONE, ratio);
    assertThat(positive.getFirst().amount()).isEqualByComparingTo("0.166666666666666667");
    var negative = allocation.allocate(BigDecimal.ONE.negate(), ratio);
    assertThat(negative.getFirst().amount()).isEqualByComparingTo("-0.166666666666666667");
    assertThat(negative).allSatisfy(part -> assertThat(part.amount()).isNotPositive());
    assertThat(
            negative.stream().map(Allocation.Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("-1");
  }

  @Test
  void sourceProductsFinerThanDivisionScaleKeepTheirSignAndExactRemainder() {
    var weights =
        java.util.stream.IntStream.rangeClosed(1, 3)
            .mapToObj(index -> new Allocation.Weight(new UUID(0, index), BigDecimal.ONE))
            .toList();
    var total = new BigDecimal("0.0000000000000000008");
    var positive = new Allocation().allocate(total, weights);
    var negative = new Allocation().allocate(total.negate(), weights);
    assertThat(positive).allSatisfy(part -> assertThat(part.amount()).isNotNegative());
    assertThat(negative).allSatisfy(part -> assertThat(part.amount()).isNotPositive());
    assertThat(
            positive.stream().map(Allocation.Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(total);
    assertThat(
            negative.stream().map(Allocation.Part::amount).reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo(total.negate());
  }

  @Test
  void returnedMicroAmountsFollowCumulativeCoverageAndPreserveTheFinalRemainder() {
    var allocation = new Allocation();
    var original = new BigDecimal("0.000000000000000003");
    BigDecimal restored = BigDecimal.ZERO;
    List<BigDecimal> parts = new ArrayList<>();
    for (int index = 1; index <= 5; index++) {
      var part =
          allocation.returnCost(
              original, new BigDecimal("5"), BigDecimal.valueOf(index), restored, index == 5);
      parts.add(part);
      restored = restored.add(part);
    }
    assertThat(parts)
        .containsExactly(
            new BigDecimal("0.000000000000000001"),
            BigDecimal.ZERO.setScale(18),
            new BigDecimal("0.000000000000000001"),
            BigDecimal.ZERO.setScale(18),
            new BigDecimal("0.000000000000000001"));
    assertThat(restored).isEqualByComparingTo(original);
    var finer = new BigDecimal("0.0000000000000000008");
    assertThat(
            allocation.returnCost(
                finer, new BigDecimal("3"), new BigDecimal("2"), BigDecimal.ZERO, false))
        .isEqualByComparingTo(finer);
    assertThat(allocation.returnCost(finer, new BigDecimal("3"), new BigDecimal("3"), finer, true))
        .isZero();
  }

  @Test
  void unidentifiedPartialReturnUsesOriginalShareAndCannotExceedOriginalCost() {
    Allocation allocation = new Allocation();
    assertThat(
            allocation.returnCost(
                new BigDecimal("300"), new BigDecimal("2"), BigDecimal.ONE, BigDecimal.ZERO, false))
        .isEqualByComparingTo("150");
    assertThatThrownBy(
            () ->
                allocation.returnCost(
                    new BigDecimal("300"),
                    new BigDecimal("2"),
                    BigDecimal.ONE,
                    new BigDecimal("200"),
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
