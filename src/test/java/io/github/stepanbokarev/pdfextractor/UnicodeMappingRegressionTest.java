package io.github.stepanbokarev.pdfextractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnicodeMappingRegressionTest {

  @TempDir private Path tempDir;

  @Test
  void fixtureHasVisibleGlyphsThatCannotBeDecodedAsUnicode() throws Exception {
    Path input = createInput();
    try (PDDocument document = PDDocument.load(input.toFile())) {
      assertThat(document.getPage(0).getResources().getFont(COSName.getPDFName("F2")).toUnicode(0))
          .isNull();
      String nativeText = new PDFTextStripper().getText(document);
      assertThat(nativeText).hasSizeGreaterThan(80).contains(UnmappedGlyphFixture.MAPPED_TEXT);
      assertThat(nativeText).doesNotContain(UnmappedGlyphFixture.VISIBLE_TEXT);
    }
  }

  @Test
  void autoRecoversUnmappedGlyphsDespiteLongReadableNativeText() throws Exception {
    Path input = createInput();
    Path output = tempDir.resolve("recovered.md");
    RunResult result = run(input, output, "auto", "eng");
    assertThat(result.exitCode()).as(result.errors()).isZero();
    assertThat(Files.readString(output))
        .contains(UnmappedGlyphFixture.VISIBLE_TEXT, UnmappedGlyphFixture.MAPPED_TEXT);
  }

  @Test
  void refusesSilentCorruptionWhenOcrIsDisabled() throws Exception {
    Path input = createInput();
    Path output = tempDir.resolve("corrupt.md");
    RunResult result = run(input, output, "off", "eng");
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.errors()).contains("Page 1", "Unicode", "OCR");
    assertThat(output).doesNotExist();
  }

  @Test
  void refusesSilentCorruptionWhenOcrLanguageIsUnavailable() throws Exception {
    Path input = createInput();
    Path output = tempDir.resolve("corrupt.md");
    RunResult result = run(input, output, "auto", "unavailable_test_language");
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.errors()).contains("Page 1", "Unicode", "OCR");
    assertThat(output).doesNotExist();
  }

  @Test
  void preservesCharactersRecoveredByPdfBoxAdditionalGlyphListWithoutOcr() throws Exception {
    Path input = tempDir.resolve("additional.pdf");
    Path output = tempDir.resolve("additional.md");
    UnmappedGlyphFixture.createWithAdditionalGlyph(input);
    RunResult result = run(input, output, "off", "eng");
    assertThat(result.exitCode()).as(result.errors()).isZero();
    assertThat(Files.readString(output)).contains("\u00a9", UnmappedGlyphFixture.MAPPED_TEXT);
  }

  @Test
  void combinesFolderWithRecoveredUnicodeText() throws Exception {
    Path folder = Files.createDirectories(tempDir.resolve("pdfs"));
    UnmappedGlyphFixture.create(folder.resolve("unmapped.pdf"));
    Path output = tempDir.resolve("combined.md");
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int exitCode =
        new PdfExtractorApplication(
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors))
            .run(
                new String[] {
                  "combine",
                  folder.toString(),
                  "--output-file",
                  output.toString(),
                  "--ocr-language",
                  "eng"
                });
    assertThat(exitCode).as(errors.toString(StandardCharsets.UTF_8)).isZero();
    assertThat(Files.readString(output))
        .contains("PATH: unmapped.md", UnmappedGlyphFixture.VISIBLE_TEXT);
  }

  private Path createInput() throws Exception {
    Path input = tempDir.resolve("unmapped.pdf");
    UnmappedGlyphFixture.create(input);
    return input;
  }

  private RunResult run(Path input, Path output, String mode, String language) {
    ByteArrayOutputStream errors = new ByteArrayOutputStream();
    int exitCode =
        new PdfExtractorApplication(
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors))
            .run(
                new String[] {
                  "extract",
                  input.toString(),
                  "--output-file",
                  output.toString(),
                  "--ocr-mode",
                  mode,
                  "--ocr-language",
                  language
                });
    return new RunResult(exitCode, errors.toString(StandardCharsets.UTF_8));
  }

  private record RunResult(int exitCode, String errors) {}
}
