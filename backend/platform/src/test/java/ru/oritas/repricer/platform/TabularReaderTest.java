package ru.oritas.repricer.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TabularReaderTest {
  @TempDir Path directory;

  @Test
  void csvPreservesTextIdentifiersExactDecimalsQuotedDelimitersAndHiddenNewlines() throws Exception {
    var rows = new ArrayList<TabularReader.Row>();
    new TabularReader().csv(input("\ufeffsku;cost;note\r\n000123;9007199254740993.01;\"a;b\nq\"\r\n"),
        ';', rows::add);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).cells()).extracting(TabularReader.Cell::text)
        .containsExactly("000123", "9007199254740993.01", "a;b\nq");
  }

  @Test
  void csvRejectsInvalidEncodingAndOversizedCellsBeforeReturningThatRow() {
    byte[] invalid = {'a', '\n', (byte) 0xc3, (byte) 0x28};
    assertThatThrownBy(() -> new TabularReader().csv(new ByteArrayInputStream(invalid), ';', row -> {}))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> new TabularReader().csv(input("h\n" + "a".repeat(32769)), ';', row -> {}))
        .isInstanceOf(IOException.class);
    assertThatThrownBy(() -> new TabularReader().csv(input("h\n" + "я".repeat(16385)), ';', row -> {}))
        .isInstanceOf(IOException.class);
  }

  @Test
  void xlsxReadsTextAndExactNumericCellWithoutLoadingWorkbook() throws Exception {
    Path source = workbook("""
        <row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1" t="inlineStr"><is><t>cost</t></is></c></row>
        <row r="2" hidden="1"><c r="A2" t="s"><v>1</v></c><c r="B2"><v>123.45</v></c></row>
        """);
    List<TabularReader.Row> rows = new ArrayList<>();
    new XlsxTableReader().read(source, null, directory, rows::add);
    assertThat(rows).hasSize(2);
    assertThat(rows.get(1).cells()).containsExactly(
        new TabularReader.Cell("000123", false), new TabularReader.Cell("123.45", true));
    try (var paths = Files.list(directory)) {
      assertThat(paths.filter(path -> path.getFileName().toString().startsWith("shared-")).count())
          .isZero();
    }
  }

  @Test
  void xlsxRejectsFormulasEvenWithCachedValueAndCleansSharedStringFiles() throws Exception {
    Path source = workbook("<row r=\"1\"><c r=\"A1\"><f>1+1</f><v>2</v></c></row>");
    assertThatThrownBy(() -> new XlsxTableReader().read(source, null, directory, row -> {}))
        .isInstanceOf(IOException.class).hasMessageContaining("formulas");
    try (var paths = Files.list(directory)) {
      assertThat(paths.filter(path -> path.getFileName().toString().startsWith("shared-")).count())
          .isZero();
    }
  }

  @Test
  void xlsxRejectsLongNumericCellsAndXmlEntities() throws Exception {
    Path large = workbook("<row><c r=\"A1\"><v>9007199254740993</v></c></row>");
    assertThatThrownBy(() -> new XlsxTableReader().read(large, null, directory, row -> {}))
        .isInstanceOf(IOException.class).hasMessageContaining("15 digits");
    Path entity = directory.resolve("entity.xlsx");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(entity))) {
      part(zip, "xl/workbook.xml", "<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///no-read'>]><x>&e;</x>");
    }
    assertThatThrownBy(() -> new XlsxTableReader().read(entity, null, directory, row -> {}))
        .isInstanceOf(IOException.class);
  }

  private Path workbook(String rows) throws IOException {
    Path file = Files.createTempFile(directory, "workbook-", ".xlsx");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
      part(zip, "xl/workbook.xml", """
          <workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets><sheet name="Import" r:id="rId1"/></sheets></workbook>
          """);
      part(zip, "xl/_rels/workbook.xml.rels",
          "<Relationships><Relationship Id=\"rId1\" Target=\"worksheets/sheet1.xml\"/></Relationships>");
      part(zip, "xl/sharedStrings.xml", "<sst><si><t>sku</t></si><si><t>000123</t></si></sst>");
      part(zip, "xl/worksheets/sheet1.xml", "<worksheet><sheetData>" + rows + "</sheetData></worksheet>");
    }
    return file;
  }

  private static void part(ZipOutputStream zip, String name, String body) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(body.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  private static ByteArrayInputStream input(String text) {
    return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
  }
}
