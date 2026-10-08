package ru.oritas.repricer.platform;

import com.ctc.wstx.api.WstxInputProperties;
import com.ctc.wstx.stax.WstxInputFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import ru.oritas.repricer.platform.TabularReader.Cell;
import ru.oritas.repricer.platform.TabularReader.Row;
import ru.oritas.repricer.platform.TabularReader.RowConsumer;

/** Streaming OOXML values; shared strings use an on-disk index instead of a heap-sized workbook. */
public final class XlsxTableReader {
  private static final long MAX_EXPANDED = 500L * 1024 * 1024;
  private final XMLInputFactory xml;

  public XlsxTableReader() {
    xml = new WstxInputFactory();
    xml.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    xml.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    xml.setProperty(WstxInputProperties.P_MAX_ATTRIBUTE_SIZE, TabularReader.MAX_CELL_BYTES);
    xml.setProperty(WstxInputProperties.P_MAX_ATTRIBUTES_PER_ELEMENT, 100);
    xml.setProperty(WstxInputProperties.P_MAX_ELEMENT_DEPTH, 64);
    xml.setProperty(WstxInputProperties.P_MAX_TEXT_LENGTH, TabularReader.MAX_CELL_BYTES);
    xml.setXMLResolver((publicId, systemId, base, namespace) -> {
      throw new XMLStreamException("External XML entities are forbidden");
    });
  }

  public void read(Path source, String selectedSheet, Path scratch, RowConsumer consumer)
      throws IOException {
    long rawSize = Files.size(source);
    if (rawSize > 100L * 1024 * 1024) {
      throw new TableFormatException("Import exceeds 100 MiB");
    }
    try (ZipFile zip = new ZipFile(source.toFile(), StandardCharsets.UTF_8);
        SharedStrings strings = new SharedStrings(scratch)) {
      Archive archive = new Archive(zip, rawSize);
      List<Sheet> sheets = new ArrayList<>();
      Map<String, String> relationships = new HashMap<>();
      Set<String> parsed = new HashSet<>();
      scan(archive, "xl/workbook.xml", reader -> {
        if (start(reader, "sheet")) {
          sheets.add(new Sheet(attribute(reader, "name"),
              reader.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "id")));
          if (sheets.size() > 1000) {
            throw new TableFormatException("Workbook sheet limit exceeded");
          }
        }
      });
      parsed.add("xl/workbook.xml");
      scan(archive, "xl/_rels/workbook.xml.rels", reader -> {
        if (start(reader, "Relationship")) {
          rejectExternal(reader);
          String target = attribute(reader, "Target");
          String normalized = target.startsWith("/") ? target.substring(1) : "xl/" + target;
          if (normalized.contains("..") || normalized.contains("\\") || normalized.contains(":")) {
            throw new TableFormatException("Invalid workbook relationship");
          }
          if (relationships.put(attribute(reader, "Id"), normalized) != null) {
            throw new TableFormatException("Duplicate relationship");
          }
        }
      });
      parsed.add("xl/_rels/workbook.xml.rels");
      if (sheets.isEmpty() || (sheets.size() > 1 && (selectedSheet == null || selectedSheet.isBlank()))) {
        throw new TableFormatException("Select exactly one workbook sheet");
      }
      Sheet chosen = selectedSheet == null || selectedSheet.isBlank() ? sheets.getFirst()
          : sheets.stream().filter(sheet -> sheet.name().equals(selectedSheet)).findFirst()
              .orElseThrow(() -> new TableFormatException("Selected sheet does not exist"));
      String sheetPath = relationships.get(chosen.relationship());
      if (sheetPath == null) {
        throw new TableFormatException("Worksheet relationship is missing");
      }
      if (archive.entries.containsKey("xl/sharedStrings.xml")) {
        StringBuilder value = new StringBuilder();
        boolean[] text = {false};
        scan(archive, "xl/sharedStrings.xml", reader -> {
          if (start(reader, "si")) {
            value.setLength(0);
          } else if (start(reader, "t")) {
            text[0] = true;
          } else if (reader.isCharacters() && text[0]) {
            append(value, reader);
          } else if (end(reader, "t")) {
            text[0] = false;
          } else if (end(reader, "si")) {
            strings.add(value.toString());
          }
        });
        parsed.add("xl/sharedStrings.xml");
      }
      SheetValues handler = new SheetValues(strings, consumer);
      scan(archive, sheetPath, handler::accept);
      parsed.add(sheetPath);
      if (handler.rows < 2) {
        throw new TableFormatException("Import has no data rows");
      }
      for (String name : archive.entries.keySet()) {
        if (parsed.contains(name)) {
          continue;
        }
        if (name.endsWith(".xml") || name.endsWith(".rels")) {
          scan(archive, name, reader -> {
            if (reader.isStartElement()) {
              rejectExternal(reader);
              if (reader.getLocalName().equals("mergeCell") || reader.getLocalName().equals("f")) {
                throw new TableFormatException("Merged cells and formulas are forbidden");
              }
              for (int index = 0; index < reader.getAttributeCount(); index++) {
                if (reader.getAttributeValue(index).contains("macroEnabled")) {
                  throw new TableFormatException("Macros are forbidden");
                }
              }
            }
          });
        } else {
          try (InputStream input = archive.open(name)) {
            input.transferTo(OutputStream.nullOutputStream());
          }
        }
      }
    } catch (XMLStreamException exception) {
      throw new TableFormatException("Invalid or unsafe XLSX XML", exception);
    }
  }

  private void scan(Archive archive, String name, XmlConsumer consumer)
      throws IOException, XMLStreamException {
    try (InputStream input = archive.open(name)) {
      XMLStreamReader reader = xml.createXMLStreamReader(input, StandardCharsets.UTF_8.name());
      int depth = 0;
      try {
        while (reader.hasNext()) {
          int event = reader.next();
          if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
            throw new TableFormatException("XML declarations and entities are forbidden");
          }
          if (reader.isStartElement() && ++depth > 64) {
            throw new TableFormatException("XML nesting limit exceeded");
          }
          consumer.accept(reader);
          if (reader.isEndElement()) {
            depth--;
          }
        }
        input.transferTo(OutputStream.nullOutputStream());
      } finally {
        reader.close();
      }
    }
  }

  private static void rejectExternal(XMLStreamReader reader) throws IOException {
    if ("External".equals(reader.getAttributeValue(null, "TargetMode"))) {
      throw new TableFormatException("External relationships are forbidden");
    }
  }

  private static boolean start(XMLStreamReader reader, String name) {
    return reader.isStartElement() && reader.getLocalName().equals(name);
  }

  private static boolean end(XMLStreamReader reader, String name) {
    return reader.isEndElement() && reader.getLocalName().equals(name);
  }

  private static String attribute(XMLStreamReader reader, String name) throws IOException {
    String value = reader.getAttributeValue(null, name);
    if (value == null || value.length() > TabularReader.MAX_CELL_BYTES) {
      throw new TableFormatException("Missing or oversized workbook attribute");
    }
    return value;
  }

  private static void append(StringBuilder builder, XMLStreamReader reader) throws IOException {
    if (reader.getTextLength() > TabularReader.MAX_CELL_BYTES - builder.length()) {
      throw new TableFormatException("Workbook cell exceeds its limit");
    }
    builder.append(reader.getTextCharacters(), reader.getTextStart(), reader.getTextLength());
  }

  private record Sheet(String name, String relationship) {}

  @FunctionalInterface
  private interface XmlConsumer {
    void accept(XMLStreamReader reader) throws IOException;
  }

  private static final class SheetValues {
    private final SharedStrings strings;
    private final RowConsumer consumer;
    private final List<Cell> cells = new ArrayList<>();
    private final StringBuilder value = new StringBuilder();
    private String type;
    private int column;
    private int rows;
    private boolean inValue;

    private SheetValues(SharedStrings strings, RowConsumer consumer) {
      this.strings = strings;
      this.consumer = consumer;
    }

    private void accept(XMLStreamReader reader) throws IOException {
      if (start(reader, "mergeCell") || start(reader, "f")) {
        throw new TableFormatException("Merged cells and formulas are forbidden");
      }
      if (start(reader, "row")) {
        if (++rows > TabularReader.MAX_ROWS) {
          throw new TableFormatException("Import row limit exceeded");
        }
        cells.clear();
      } else if (start(reader, "c")) {
        String reference = attribute(reader, "r");
        int number = 0;
        int position = 0;
        while (position < reference.length() && reference.charAt(position) >= 'A'
            && reference.charAt(position) <= 'Z') {
          number = number * 26 + reference.charAt(position++) - 'A' + 1;
          if (number > TabularReader.MAX_COLUMNS) {
            throw new TableFormatException("Import column limit exceeded");
          }
        }
        column = number - 1;
        if (column < cells.size() || column < 0) {
          throw new TableFormatException("Duplicate or unordered workbook cell");
        }
        while (cells.size() < column) {
          cells.add(new Cell("", false));
        }
        type = reader.getAttributeValue(null, "t");
        if (type != null && !Set.of("s", "inlineStr", "str", "n").contains(type)) {
          throw new TableFormatException("Unsupported or error workbook cell");
        }
        value.setLength(0);
      } else if (start(reader, "v") || start(reader, "t")) {
        inValue = true;
      } else if (reader.isCharacters() && inValue) {
        append(value, reader);
      } else if (end(reader, "v") || end(reader, "t")) {
        inValue = false;
      } else if (end(reader, "c")) {
        String text = value.toString();
        if ("s".equals(type)) {
          try {
            text = strings.get(Integer.parseInt(text));
          } catch (NumberFormatException exception) {
            throw new TableFormatException("Invalid shared string index");
          }
        }
        boolean numeric = (type == null || type.equals("n")) && !text.isEmpty();
        if (numeric) {
          try {
            if (new BigDecimal(text).precision() > 15) {
              throw new TableFormatException("Excel numeric cells above 15 digits must be text");
            }
          } catch (NumberFormatException exception) {
            throw new TableFormatException("Invalid numeric workbook value");
          }
        }
        TabularReader.validateCell(text);
        cells.add(new Cell(text, numeric));
      } else if (end(reader, "row")) {
        consumer.accept(new Row(rows, cells));
      }
    }
  }

  private static final class SharedStrings implements AutoCloseable {
    private final Path dataPath;
    private final Path indexPath;
    private final RandomAccessFile data;
    private final RandomAccessFile index;
    private int count;

    private SharedStrings(Path directory) throws IOException {
      dataPath = Files.createTempFile(directory, "shared-", ".data");
      indexPath = Files.createTempFile(directory, "shared-", ".index");
      data = new RandomAccessFile(dataPath.toFile(), "rw");
      index = new RandomAccessFile(indexPath.toFile(), "rw");
    }

    private void add(String text) throws IOException {
      TabularReader.validateCell(text);
      if (++count > 5_000_000) {
        throw new TableFormatException("Shared string count exceeds the workbook limit");
      }
      byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
      index.writeLong(data.length());
      index.writeInt(bytes.length);
      data.write(bytes);
      if (data.length() + index.length() > 1024L * 1024 * 1024) {
        throw new TableFormatException("Shared string temporary storage exceeded");
      }
    }

    private String get(int position) throws IOException {
      if (position < 0 || position >= count) {
        throw new TableFormatException("Shared string index is outside the table");
      }
      index.seek(position * 12L);
      long offset = index.readLong();
      int length = index.readInt();
      byte[] bytes = new byte[length];
      data.seek(offset);
      data.readFully(bytes);
      return new String(bytes, StandardCharsets.UTF_8);
    }

    @Override
    public void close() throws IOException {
      try {
        data.close();
      } finally {
        try {
          index.close();
        } finally {
          Files.deleteIfExists(dataPath);
          Files.deleteIfExists(indexPath);
        }
      }
    }
  }

  private static final class Archive {
    private final ZipFile zip;
    private final Map<String, ZipEntry> entries = new HashMap<>();
    private final long limit;
    private long expanded;

    private Archive(ZipFile zip, long rawSize) throws IOException {
      this.zip = zip;
      limit = Math.min(MAX_EXPANDED, Math.multiplyExact(rawSize, 100));
      var enumeration = zip.entries();
      int parts = 0;
      while (enumeration.hasMoreElements()) {
        ZipEntry entry = enumeration.nextElement();
        if (++parts > 1000) {
          throw new TableFormatException("Workbook archive part limit exceeded");
        }
        if (entry.isDirectory()) {
          continue;
        }
        String name = entry.getName();
        if (entries.size() >= 1000 || name.length() > 1024 || name.contains("..")
            || name.contains("\\") || name.startsWith("/")
            || name.toLowerCase(java.util.Locale.ROOT).contains("vbaproject")
            || name.contains("externalLinks/") || entries.put(name, entry) != null) {
          throw new TableFormatException("Unsafe or oversized workbook archive");
        }
        if (entry.getSize() < 0 || entry.getSize() > limit) {
          throw new TableFormatException("Invalid workbook entry size");
        }
      }
    }

    private InputStream open(String name) throws IOException {
      ZipEntry entry = entries.get(name);
      if (entry == null) {
        throw new TableFormatException("Required workbook part is missing");
      }
      return new FilterInputStream(zip.getInputStream(entry)) {
        private final CRC32 crc = new CRC32();
        private long read;
        private boolean verified;

        @Override
        public int read() throws IOException {
          int value = in.read();
          if (value == -1) {
            verify();
          } else {
            crc.update(value);
            consume(1);
          }
          return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
          int size = in.read(bytes, offset, length);
          if (size == -1) {
            verify();
          } else if (size > 0) {
            crc.update(bytes, offset, size);
            consume(size);
          }
          return size;
        }

        private void consume(int size) throws IOException {
          read += size;
          expanded += size;
          if (read > entry.getSize() || expanded > limit) {
            throw new TableFormatException("Workbook expansion limit exceeded");
          }
        }

        private void verify() throws IOException {
          if (!verified && (read != entry.getSize() || crc.getValue() != entry.getCrc())) {
            throw new TableFormatException("Workbook part checksum does not match");
          }
          verified = true;
        }
      };
    }
  }
}
