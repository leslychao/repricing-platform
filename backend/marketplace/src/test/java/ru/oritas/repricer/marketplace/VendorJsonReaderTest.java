package ru.oritas.repricer.marketplace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class VendorJsonReaderTest {
  @Test
  void jsonLinesPreserveAnUnterminatedLastRecordAndRejectMalformedOrOversizedLines()
      throws Exception {
    List<String> values = new ArrayList<>();
    long count =
        VendorJsonReader.reportRowsSized(
            input("{\"id\":\"первый\"}\r\n{\"id\":\"last\"}"),
            VendorJsonReader.RecordFormat.JSON_LINES,
            (row, bytes) -> values.add(row.get("id").getAsString()));
    assertThat(count).isEqualTo(2);
    assertThat(values).containsExactly("первый", "last");
    for (String invalid :
        List.of(
            "\n",
            "{} {}\n",
            "{\"id\":\"x\nvalue\"}",
            "{\"id\":\"" + "x".repeat(1024 * 1024) + "\"}")) {
      assertThatThrownBy(
              () ->
                  VendorJsonReader.reportRowsSized(
                      input(invalid), VendorJsonReader.RecordFormat.JSON_LINES, (row, bytes) -> {}))
          .isInstanceOf(IOException.class);
    }
    byte[] invalidUtf8 = {'{', '"', 'x', '"', ':', '"', (byte) 0xc3, (byte) 0x28, '"', '}'};
    assertThatThrownBy(
            () ->
                VendorJsonReader.reportRowsSized(
                    new ByteArrayInputStream(invalidUtf8),
                    VendorJsonReader.RecordFormat.JSON_LINES,
                    (row, bytes) -> {}))
        .isInstanceOf(IOException.class);
  }

  @Test
  void parsesAReportLargerThanTheProcessHeapWithoutCollectingRows() throws Exception {
    String javaExecutable =
        java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var process =
        new ProcessBuilder(
                javaExecutable,
                "-Xmx64m",
                "-cp",
                System.getProperty("java.class.path"),
                LargeReportProbe.class.getName())
            .inheritIO()
            .start();
    try {
      assertThat(process.waitFor(90, TimeUnit.SECONDS))
          .as("bounded report parser deadline")
          .isTrue();
      assertThat(process.exitValue()).as("256 MiB JSONL parsed with a 64 MiB heap").isZero();
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  public static final class LargeReportProbe {
    private LargeReportProbe() {}

    public static void main(String[] arguments) throws IOException {
      byte[] record =
          ("{\"value\":\"" + "x".repeat(32756) + "\"}\n").getBytes(StandardCharsets.UTF_8);
      int repetitions = 8192;
      long[] consumed = {0};
      try (InputStream source =
          new InputStream() {
            private long remaining = (long) record.length * repetitions;
            private int offset;

            @Override
            public int read() {
              if (remaining == 0) {
                return -1;
              }
              int value = record[offset++] & 0xff;
              remaining--;
              if (offset == record.length) {
                offset = 0;
              }
              return value;
            }

            @Override
            public int read(byte[] target, int start, int length) {
              if (length == 0) {
                return 0;
              }
              if (remaining == 0) {
                return -1;
              }
              int count = (int) Math.min(Math.min(length, record.length - offset), remaining);
              System.arraycopy(record, offset, target, start, count);
              remaining -= count;
              offset = (offset + count) % record.length;
              return count;
            }
          }) {
        long rows =
            VendorJsonReader.reportRowsSized(
                source, VendorJsonReader.RecordFormat.JSON_LINES, (row, bytes) -> consumed[0]++);
        if (rows != repetitions || consumed[0] != repetitions) {
          throw new IOException("Report lost or duplicated records");
        }
      }
    }
  }

  @Test
  void readsRecordsAndTrailingPagingWithoutMaterializingThePage() throws IOException {
    List<String> offers = new ArrayList<>();
    try (var input =
        input(
            """
            {"status":"OK","result":{"offers":[{"offerId":"a"},{"offerId":"б"}],
              "paging":{"nextPageToken":"next"},"totalCount":2}}
            """)) {
      var page =
          VendorJsonReader.parse(
              input, "result.offers", item -> offers.add(item.get("offerId").getAsString()));
      assertThat(offers).containsExactly("a", "б");
      assertThat(page.records()).isEqualTo(2);
      assertThat(page.total()).isEqualTo(2);
      assertThat(page.cursor()).isEqualTo("next");
    }
  }

  @Test
  void readsOzonContinuationAndNewTotalWithoutLosingIntegerPrecision() throws IOException {
    var page =
        VendorJsonReader.parse(
            input(
                """
                {"postings":[{"order_id":9007199254740993}],"has_next":true,"cursor":"next",
                  "total_items":"9007199254740993","total":1}
                """),
            "postings",
            item -> assertThat(item.get("order_id").getAsString()).isEqualTo("9007199254740993"));
    assertThat(page.hasNext()).isTrue();
    assertThat(page.total()).isEqualTo(9007199254740993L);
    assertThat(page.cursor()).isEqualTo("next");
    assertThatThrownBy(
            () ->
                VendorJsonReader.parse(
                    input("{\"postings\":[],\"has_next\":\"false\"}"), "postings", item -> {}))
        .isInstanceOf(IOException.class);
  }

  @Test
  void aTruncatedPageCannotBecomeACompleteEmptyPublication() {
    assertThatThrownBy(
            () ->
                VendorJsonReader.parse(
                    input("{\"result\":{\"offers\":["), "result.offers", item -> {}))
        .isInstanceOf(IOException.class);
  }

  @Test
  void rejectsMalformedUtf8InsteadOfReplacingSkuBytes() {
    byte[] prefix = "{\"result\":{\"offers\":[{\"offerId\":\"".getBytes(StandardCharsets.UTF_8);
    byte[] suffix = "\"}]}}".getBytes(StandardCharsets.UTF_8);
    byte[] body = new byte[prefix.length + suffix.length + 2];
    System.arraycopy(prefix, 0, body, 0, prefix.length);
    body[prefix.length] = (byte) 0xc3;
    body[prefix.length + 1] = (byte) 0x28;
    System.arraycopy(suffix, 0, body, prefix.length + 2, suffix.length);
    assertThatThrownBy(
            () ->
                VendorJsonReader.parse(new ByteArrayInputStream(body), "result.offers", item -> {}))
        .isInstanceOf(IOException.class);
  }

  @Test
  void boundsAnIndividualSupplierRecordBeforeBuildingAnUnboundedTree() {
    String body = "{\"items\":[{\"name\":\"" + "x".repeat(1024 * 1024) + "\"}]}";
    assertThatThrownBy(() -> VendorJsonReader.parse(input(body), "items", item -> {}))
        .hasMessageContaining("1 MiB");
  }

  @Test
  void refusesDuplicateCollectionsAndExcessivePageCardinality() {
    assertThatThrownBy(
            () -> VendorJsonReader.parse(input("{\"items\":[],\"items\":[]}"), "items", item -> {}))
        .isInstanceOf(IOException.class);
    String body =
        "{\"items\":[" + String.join(",", java.util.Collections.nCopies(1001, "{}")) + "]}";
    assertThatThrownBy(() -> VendorJsonReader.parse(input(body), "items", item -> {}))
        .isInstanceOf(IOException.class);
  }

  private static ByteArrayInputStream input(String value) {
    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
  }
}
