package ru.oritas.repricer.marketplace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CategoryTreeReaderTest {
  @Test
  void childrenBeforeIdentityStillBindToTheirExactParent() throws Exception {
    String source =
        """
        {"result":[{"children":[{"type_name":"Кружка","type_id":30,"children":[],
          "disabled":false}],"disabled":false,"description_category_id":20,"category_name":"Посуда"}]}
        """;
    var records = new ArrayList<CategoryTreeReader.Node>();
    assertEquals(2, CategoryTreeReader.parse(stream(source), true, records::add));
    assertEquals(1, records.getFirst().parentSequence());
    assertEquals("TYPE", records.getFirst().kind());
    assertEquals("20", records.getLast().externalId());
  }

  @Test
  void excessiveJsonNestingAndCumulativeScalarBytesAreRejected() {
    String node = "{\"id\":1,\"name\":\"x\",\"children\":[";
    String deep =
        "{\"status\":\"OK\",\"result\":"
            + node.repeat(35)
            + "{\"id\":2,\"name\":\"leaf\"}"
            + "]}".repeat(35)
            + "}";
    assertThrows(
        IOException.class, () -> CategoryTreeReader.parse(stream(deep), false, ignored -> {}));
    String large =
        "{\"status\":\"OK\",\"result\":{\"id\":1,\"name\":\"x\",\"unknownOne\":\""
            + "a".repeat(600_000)
            + "\",\"unknownTwo\":\""
            + "b".repeat(600_000)
            + "\"}}";
    assertThrows(
        IOException.class, () -> CategoryTreeReader.parse(stream(large), false, ignored -> {}));
  }

  @Test
  void truncatedOrRepeatedResultsNeverClaimWholeTree() {
    assertThrows(
        IOException.class,
        () ->
            CategoryTreeReader.parse(
                stream("{\"status\":\"OK\",\"result\":{\"id\":1,\"name\":\"x\"}"),
                false,
                ignored -> {}));
    assertThrows(
        IOException.class,
        () ->
            CategoryTreeReader.parse(
                stream(
                    "{\"status\":\"OK\",\"result\":{\"id\":1,\"name\":\"x\"},\"result\":{\"id\":2,\"name\":\"y\"}}"),
                false,
                ignored -> {}));
  }

  @Test
  void otherVendorsKeysDoNotProveACategoryIdentity() {
    assertThrows(IOException.class,()->CategoryTreeReader.parse(
        stream("{\"result\":[{\"id\":1,\"name\":\"x\",\"disabled\":false}]}"),true,ignored->{}));
    assertThrows(IOException.class,()->CategoryTreeReader.parse(
        stream("{\"status\":\"OK\",\"result\":{\"description_category_id\":1,\"category_name\":\"x\"}}"),false,ignored->{}));
  }

  @Test
  void deadlineInterruptionRemainsDistinguishableFromInvalidRaw() {
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          InterruptedIOException.class,
          () ->
              CategoryTreeReader.parse(
                  stream("{\"status\":\"OK\",\"result\":{\"id\":1,\"name\":\"x\"}}"),
                  false,
                  ignored -> {}));
    } finally {
      Thread.interrupted();
    }
  }

  private static ByteArrayInputStream stream(String json) {
    return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
  }
}
