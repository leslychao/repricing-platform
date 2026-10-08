package ru.oritas.repricer.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExportWriterTest {
  @TempDir Path directory;

  @Test
  void xlsxRoundTripKeepsLongMoneyAndIdentifiersAsExactTextAndFormulaAsText() throws Exception {
    Path file = directory.resolve("result.xlsx");
    try (var stream = Files.newOutputStream(file);
        var writer = new XlsxTableWriter(stream, List.of("sku", "amount", "note"))) {
      writer.row(List.of(CsvTableWriter.Cell.text("000123"),
          CsvTableWriter.Cell.number(new BigDecimal("9007199254740993.01")),
          CsvTableWriter.Cell.text("=IMPORTDATA(\"https://invalid\")")));
    }
    var rows = new ArrayList<TabularReader.Row>();
    new XlsxTableReader().read(file, null, directory, rows::add);
    assertThat(rows).hasSize(2);
    assertThat(rows.getLast().cells()).extracting(TabularReader.Cell::text)
        .containsExactly("000123", "9007199254740993.01", "=IMPORTDATA(\"https://invalid\")");
    assertThat(rows.getLast().cells()).allMatch(cell -> !cell.numeric());
  }

  @Test
  void csvNeutralizesFormulaTextButKeepsExactNegativeMoney() throws Exception {
    var output = new StringWriter();
    var writer = new CsvTableWriter(output, List.of("text", "amount"));
    writer.row(List.of(CsvTableWriter.Cell.text("  =1+1"),
        CsvTableWriter.Cell.number(new BigDecimal("-999999999999999.01"))));
    assertThat(output.toString()).contains("\"'  =1+1\";\"-999999999999999.01\"");
  }

  @Test
  void xlsxLimitNeverSilentlyTruncatesData() throws Exception {
    try (var writer = new XlsxTableWriter(new ByteArrayOutputStream(), List.of("text"))) {
      assertThatThrownBy(() -> writer.row(List.of(CsvTableWriter.Cell.text("\n".repeat(254)))))
          .isInstanceOf(BusinessException.class).hasMessageContaining("CSV");
    }
  }

  @Test
  void producerFailureAfterClosingItsWriterCannotPublishAnEof() throws Exception {
    StoredFileService storage = mock(StoredFileService.class);
    doAnswer(call -> {
      InputStream input = call.getArgument(4, InputStream.class);
      byte[] buffer = new byte[8192];
      while (input.read(buffer) != -1) {
        // A real storage writer publishes only after EOF; this must never reach it.
      }
      throw new AssertionError("Failed producer reached a publishable EOF");
    }).when(storage).store(any(Scope.class), anyString(), anyString(), anyString(),
        any(InputStream.class), anyLong(), any(Instant.class));
    assertThatThrownBy(() -> GeneratedFile.store(storage,
        new Scope(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()),
        "REPORT", "text/csv", "test.csv", 1024, Instant.now().plusSeconds(5), output -> {
          output.write("partial".getBytes(java.nio.charset.StandardCharsets.UTF_8));
          output.close();
          throw new IOException("Failed final block");
        })).isInstanceOf(IOException.class).hasRootCauseMessage("Failed final block");
  }
}
