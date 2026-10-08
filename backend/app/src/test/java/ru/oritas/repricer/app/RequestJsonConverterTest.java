package ru.oritas.repricer.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.StringReader;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import ru.oritas.repricer.platform.JsonCodec;

class RequestJsonConverterTest {
  private final RequestJsonConverter converter = new RequestJsonConverter(new JsonCodec());

  @Test
  void financialCommandKeepsExactDecimalStringAndRequiresExplicitBooleanAndRevision() throws Exception {
    MoneyCommand value = (MoneyCommand) converter.readInternal(MoneyCommand.class,
        new StringReader("{\"amount\":\"9007199254740993.01\",\"enabled\":false,\"expectedRevision\":0}"));
    assertThat(value.amount()).isEqualByComparingTo("9007199254740993.01");
    assertThat(value.enabled()).isFalse();
    for (String invalid : new String[] {
        "{\"amount\":1.23,\"enabled\":true,\"expectedRevision\":0}",
        "{\"amount\":\"1.23\",\"expectedRevision\":0}",
        "{\"amount\":\"1.23\",\"enabled\":null,\"expectedRevision\":0}",
        "{\"amount\":\"1.23\",\"enabled\":true,\"expectedRevision\":0.5}",
        "{\"amount\":\"1.23\",\"enabled\":true,\"expectedRevision\":0,\"bypass\":true}",
        "{\"amount\":\"1\",\"amount\":\"2\",\"enabled\":true,\"expectedRevision\":0}",
        "{\"amount\":\"1e2147483647\",\"enabled\":true,\"expectedRevision\":0}"
    }) {
      assertThatThrownBy(() -> converter.readInternal(MoneyCommand.class, new StringReader(invalid)))
          .isInstanceOf(Exception.class);
    }
  }

  @Test
  void responseMoneyDoesNotSwitchToExponentialNotation() {
    assertThat(new JsonCodec().encode(new BigDecimal("0.00000001"))).isEqualTo("\"0.00000001\"");
  }

  private record MoneyCommand(BigDecimal amount, boolean enabled, long expectedRevision) {}
}
