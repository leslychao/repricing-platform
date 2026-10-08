package ru.oritas.repricer.platform;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/** One-sheet XLSX with inline strings: bounded memory, no shared-string dictionary or formulas. */
public final class XlsxTableWriter implements AutoCloseable {
  private static final String NAMESPACE =
      "http://schemas.openxmlformats.org/spreadsheetml/2006/main";
  private final ZipOutputStream zip;
  private final XMLStreamWriter xml;
  private final int columns;
  private long rows;

  public XlsxTableWriter(OutputStream output, List<String> headers) throws IOException {
    if (headers.isEmpty() || headers.size() > 100) {
      throw new IllegalArgumentException("Invalid export column count");
    }
    columns = headers.size();
    zip = new ZipOutputStream(output, StandardCharsets.UTF_8);
    try {
      entry(
          "[Content_Types].xml",
          """
          <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
          <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
          <Default Extension="xml" ContentType="application/xml"/>
          <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
          <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
          </Types>
          """);
      entry(
          "_rels/.rels",
          """
          <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="workbook" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
          </Relationships>
          """);
      entry(
          "xl/workbook.xml",
          """
          <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
          <sheets><sheet name="Repricer" sheetId="1" r:id="sheet"/></sheets></workbook>
          """);
      entry(
          "xl/_rels/workbook.xml.rels",
          """
          <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
          <Relationship Id="sheet" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
          </Relationships>
          """);
      zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
      xml = XMLOutputFactory.newFactory().createXMLStreamWriter(zip, "UTF-8");
      xml.writeStartDocument("UTF-8", "1.0");
      xml.writeStartElement("worksheet");
      xml.writeDefaultNamespace(NAMESPACE);
      xml.writeStartElement("sheetData");
      row(headers.stream().map(CsvTableWriter.Cell::text).toList());
    } catch (XMLStreamException exception) {
      zip.close();
      throw new IOException("Cannot create XLSX", exception);
    }
  }

  private void entry(String name, String content) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(content.getBytes(StandardCharsets.UTF_8));
    zip.closeEntry();
  }

  public void row(List<CsvTableWriter.Cell> cells) throws IOException {
    if (++rows > 1_000_001 || cells.size() != columns) {
      throw new IOException("Export row or column limit exceeded");
    }
    try {
      xml.writeStartElement("row");
      xml.writeAttribute("r", Long.toString(rows));
      for (int column = 0; column < cells.size(); column++) {
        CsvTableWriter.Cell cell = cells.get(column);
        String value = cell.value() == null ? "" : cell.value();
        TabularReader.validateCell(value);
        if (value.length() > 32767
            || value.chars().filter(character -> character == '\n').count() > 253
            || value
                .codePoints()
                .anyMatch(
                    character ->
                        character < 32 && character != 9 && character != 10 && character != 13
                            || character == 0xfffe
                            || character == 0xffff
                            || character >= 0xd800 && character <= 0xdfff)) {
          throw new BusinessException(
              "XLSX_CELL_LIMIT",
              422,
              "Ячейка не помещается в XLSX. Выберите CSV или уточните выборку");
        }
        boolean numeric =
            cell.numeric() && !value.isEmpty() && new BigDecimal(value).precision() <= 15;
        xml.writeStartElement("c");
        xml.writeAttribute("r", columnName(column) + rows);
        xml.writeAttribute("t", numeric ? "n" : "inlineStr");
        if (numeric) {
          xml.writeStartElement("v");
          xml.writeCharacters(value);
          xml.writeEndElement();
        } else {
          xml.writeStartElement("is");
          xml.writeStartElement("t");
          xml.writeAttribute("xml", "http://www.w3.org/XML/1998/namespace", "space", "preserve");
          xml.writeCharacters(value);
          xml.writeEndElement();
          xml.writeEndElement();
        }
        xml.writeEndElement();
      }
      xml.writeEndElement();
    } catch (XMLStreamException exception) {
      throw new IOException("Cannot write XLSX row", exception);
    }
  }

  private static String columnName(int column) {
    int first = column / 26;
    return (first == 0 ? "" : Character.toString('A' + first - 1))
        + Character.toString('A' + column % 26);
  }

  @Override
  public void close() throws IOException {
    try {
      xml.writeEndElement();
      xml.writeEndElement();
      xml.writeEndDocument();
      xml.flush();
      zip.closeEntry();
      zip.finish();
    } catch (XMLStreamException exception) {
      throw new IOException("Cannot complete XLSX", exception);
    } finally {
      zip.close();
    }
  }
}
