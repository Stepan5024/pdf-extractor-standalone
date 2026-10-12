package io.github.stepanbokarev.pdfextractor;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.PathIterator;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.PDType3Font;

/** Real visible glyph outlines with deliberately missing Unicode mappings. */
final class UnmappedGlyphFixture {

  static final String VISIBLE_TEXT = "RECOVER VISIBLE TEXT 2468";
  static final String MAPPED_TEXT = "Ordinary mapped text is readable and must be preserved.";

  public static void main(String[] args) throws Exception {
    Path directory = Files.createDirectories(Path.of(args[0]));
    create(directory.resolve("unmapped.pdf"));
  }

  static void create(Path target) throws Exception {
    create(target, VISIBLE_TEXT, false);
  }

  static void createWithAdditionalGlyph(Path target) throws Exception {
    create(target, "Copyright \u00a9 preserved.", true);
  }

  private static void create(Path target, String visibleText, boolean additionalGlyph)
      throws Exception {
    try (PDDocument document = new PDDocument();
        InputStream fontData =
            PDDocument.class.getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
      Font outlineFont = Font.createFont(Font.TRUETYPE_FONT, fontData).deriveFont(1000f);
      Map<Character, Integer> codes = new LinkedHashMap<>();
      for (char character : visibleText.toCharArray()) {
        codes.computeIfAbsent(character, ignored -> codes.size());
      }
      COSDictionary dictionary = new COSDictionary();
      dictionary.setItem(COSName.TYPE, COSName.FONT);
      dictionary.setItem(COSName.SUBTYPE, COSName.TYPE3);
      dictionary.setItem(COSName.FONT_BBOX, new PDRectangle(0, -250, 1200, 1250));
      COSArray matrix = new COSArray();
      for (float value : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
        matrix.add(new COSFloat(value));
      }
      dictionary.setItem(COSName.FONT_MATRIX, matrix);
      COSDictionary encoding = new COSDictionary();
      encoding.setItem(COSName.TYPE, COSName.ENCODING);
      COSArray differences = new COSArray();
      differences.add(org.apache.pdfbox.cos.COSInteger.ZERO);
      COSArray widths = new COSArray();
      COSDictionary charProcs = new COSDictionary();
      for (Map.Entry<Character, Integer> entry : codes.entrySet()) {
        String name = "g" + entry.getValue();
        if (additionalGlyph) {
          name =
              entry.getKey() == '\u00a9'
                  ? "circlecopyrt"
                  : org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding.INSTANCE.getName(
                      entry.getKey());
        }
        COSName glyphName = COSName.getPDFName(name);
        differences.add(glyphName);
        GlyphVector glyph =
            outlineFont.createGlyphVector(
                new FontRenderContext(null, false, false), entry.getKey().toString());
        float advance = glyph.getGlyphMetrics(0).getAdvanceX();
        widths.add(new COSFloat(advance));
        StringBuilder commands = new StringBuilder().append(advance).append(" 0 d0\n");
        PathIterator path = glyph.getOutline().getPathIterator(null, 0.25);
        double[] coordinates = new double[6];
        while (!path.isDone()) {
          int segment = path.currentSegment(coordinates);
          if (segment == PathIterator.SEG_CLOSE) {
            commands.append("h\n");
          } else {
            commands.append(coordinates[0]).append(' ').append(-coordinates[1]);
            commands.append(segment == PathIterator.SEG_MOVETO ? " m\n" : " l\n");
          }
          path.next();
        }
        commands.append("f\n");
        charProcs.setItem(glyphName, stream(document, commands.toString()));
      }
      encoding.setItem(COSName.DIFFERENCES, differences);
      dictionary.setItem(COSName.ENCODING, encoding);
      dictionary.setItem(COSName.CHAR_PROCS, charProcs);
      dictionary.setItem(COSName.WIDTHS, widths);
      dictionary.setInt(COSName.FIRST_CHAR, 0);
      dictionary.setInt(COSName.LAST_CHAR, codes.size() - 1);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), PDType1Font.HELVETICA);
      resources.put(COSName.getPDFName("F2"), new PDType3Font(dictionary));
      StringBuilder hex = new StringBuilder();
      for (char character : visibleText.toCharArray()) {
        hex.append(String.format(java.util.Locale.ROOT, "%02X", codes.get(character)));
      }
      PDPage page = new PDPage(PDRectangle.A4);
      page.setResources(resources);
      page.setContents(
          stream(
              document,
              "BT /F1 13 Tf 72 760 Td ("
                  + MAPPED_TEXT
                  + ") Tj 0 -22 Td ("
                  + MAPPED_TEXT
                  + ") Tj ET\nBT /F2 24 Tf 72 650 Td <"
                  + hex
                  + "> Tj ET\n"));
      document.addPage(page);
      document.save(target.toFile());
    }
  }

  private static PDStream stream(PDDocument document, String content) throws Exception {
    return new PDStream(
        document, new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII)));
  }
}
