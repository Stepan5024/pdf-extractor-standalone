package io.github.stepanbokarev.pdfextractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType3Font;
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class UnnamedFontRegressionTest {

  @TempDir Path tempDir;

  /** Generates the same regression fixture for the packaged-JAR smoke check. */
  public static void main(String[] args) throws Exception {
    Path input = Files.createDirectories(Path.of(args[0]));
    Path nested = Files.createDirectories(input.resolve("nested"));
    createPdf(input.resolve("named.pdf"), "Example-Regular", "Named font text.");
    createPdf(nested.resolve("unnamed.pdf"), null, "Unnamed font text.");
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"Example-Regular", "Example-Bold", "Example-Semibold"})
  void extractsTextWithOptionalFontName(String fontName) throws Exception {
    Path input = tempDir.resolve("font.pdf");
    Path output = tempDir.resolve("font.md");
    createPdf(input, fontName, "Readable text survives missing font metadata.");

    try (PDDocument document = PDDocument.load(input.toFile())) {
      assertThat(document.getPage(0).getResources().getFont(COSName.getPDFName("F1")).getName())
          .isEqualTo(fontName);
    }

    assertSuccessfulRun("extract", input.toString(), "--output-file", output.toString());
    assertThat(Files.readString(output)).contains("Readable text survives missing font metadata.");
  }

  @Test
  void combinesUnnamedFontPdfInNestedFolder() throws Exception {
    Path input = Files.createDirectories(tempDir.resolve("pdfs"));
    Path nested = Files.createDirectories(input.resolve("nested"));
    createPdf(input.resolve("named.pdf"), "Example-Regular", "Named font text.");
    createPdf(nested.resolve("unnamed.pdf"), null, "Unnamed font text.");
    Path output = tempDir.resolve("combined.md");

    assertSuccessfulRun("combine", input.toString(), "--output-file", output.toString());
    assertThat(Files.readString(output))
        .contains("PATH: named.md", "PATH: nested/unnamed.md")
        .contains("Named font text.", "Unnamed font text.");
  }

  private void assertSuccessfulRun(String... args) {
    String[] options = java.util.Arrays.copyOf(args, args.length + 2);
    options[args.length] = "--ocr-mode";
    options[args.length + 1] = "off";
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int exitCode =
        new PdfExtractorApplication(
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors))
            .run(options);
    assertThat(exitCode).as("CLI stderr: %s", errors).isZero();
    assertThat(errors.toString(StandardCharsets.UTF_8)).isEmpty();
  }

  private static void createPdf(Path target, String fontName, String text) throws Exception {
    try (PDDocument document = new PDDocument()) {
      COSDictionary dictionary = new COSDictionary();
      dictionary.setItem(COSName.TYPE, COSName.FONT);
      dictionary.setItem(COSName.SUBTYPE, COSName.TYPE3);
      if (fontName != null) {
        dictionary.setName(COSName.NAME, fontName);
      }
      dictionary.setItem(COSName.FONT_BBOX, new PDRectangle(600, 700));
      COSArray matrix = new COSArray();
      for (float value : new float[] {0.001f, 0, 0, 0.001f, 0, 0}) {
        matrix.add(new COSFloat(value));
      }
      dictionary.setItem(COSName.FONT_MATRIX, matrix);
      dictionary.setItem(COSName.ENCODING, COSName.WIN_ANSI_ENCODING);
      dictionary.setInt(COSName.FIRST_CHAR, 32);
      dictionary.setInt(COSName.LAST_CHAR, 126);
      COSArray widths = new COSArray();
      COSDictionary charProcs = new COSDictionary();
      PDStream glyph = stream(document, "600 0 d0\n0 0 500 700 re f\n");
      for (int code = 32; code <= 126; code++) {
        widths.add(COSInteger.get(600));
        charProcs.setItem(COSName.getPDFName(WinAnsiEncoding.INSTANCE.getName(code)), glyph);
      }
      dictionary.setItem(COSName.WIDTHS, widths);
      dictionary.setItem(COSName.CHAR_PROCS, charProcs);
      PDResources resources = new PDResources();
      resources.put(COSName.getPDFName("F1"), new PDType3Font(dictionary));
      PDPage page = new PDPage(PDRectangle.A4);
      page.setResources(resources);
      page.setContents(stream(document, "BT /F1 12 Tf 72 720 Td (" + text + ") Tj ET\n"));
      document.addPage(page);
      document.save(target.toFile());
    }
  }

  private static PDStream stream(PDDocument document, String content) throws Exception {
    return new PDStream(
        document, new ByteArrayInputStream(content.getBytes(StandardCharsets.US_ASCII)));
  }
}
