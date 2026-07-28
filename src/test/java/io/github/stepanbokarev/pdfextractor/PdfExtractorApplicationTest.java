package io.github.stepanbokarev.pdfextractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfExtractorApplicationTest {

  @TempDir Path tempDir;

  @Test
  void extractsMarkdownWithLinksAndTable() throws Exception {
    Path inputPdf = tempDir.resolve("sample.pdf");
    Path outputMarkdown = tempDir.resolve("sample.md");
    createSamplePdf(inputPdf);

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exitCode =
        new PdfExtractorApplication(new PrintStream(out), new PrintStream(err))
            .run(
                new String[] {
                  inputPdf.toString(),
                  "--file-format",
                  "md",
                  "--output-file",
                  outputMarkdown.toString(),
                  "--ocr-mode",
                  "off"
                });

    assertThat(exitCode).isZero();
    assertThat(err.toString()).isBlank();
    assertThat(outputMarkdown).exists();
    String markdown = Files.readString(outputMarkdown);
    assertThat(markdown).contains("# Sample Document");
    assertThat(markdown)
        .contains("This paragraph should stay together in the extracted markdown output.");
    assertThat(markdown).contains("[Example link](https://example.com/report)");
    assertThat(markdown).contains("| Quarter | Revenue |");
    assertThat(markdown).contains("| Q1 | 120 |");
  }

  @Test
  void returnsValidationExitCodeForMissingPdf() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    int exitCode =
        new PdfExtractorApplication(new PrintStream(out), new PrintStream(err))
            .run(new String[] {tempDir.resolve("missing.pdf").toString()});

    assertThat(exitCode).isEqualTo(2);
    assertThat(err.toString()).contains("PDF file not found");
  }

  @Test
  void combinesPdfFolderIntoBundleAndSplitsBackIntoMarkdownFiles() throws Exception {
    Path inputDir = tempDir.resolve("pdfs");
    Path nestedDir = inputDir.resolve("nested");
    Files.createDirectories(nestedDir);

    Path firstPdf = inputDir.resolve("alpha.pdf");
    Path secondPdf = nestedDir.resolve("beta.pdf");
    createPdf(firstPdf, "Alpha Document", List.of("Alpha body paragraph for bundle test."));
    createPdf(secondPdf, "Beta Document", List.of("Beta body paragraph for bundle test."));

    Path combinedMarkdown = tempDir.resolve("combined.md");
    ByteArrayOutputStream combineOut = new ByteArrayOutputStream();
    ByteArrayOutputStream combineErr = new ByteArrayOutputStream();
    int combineExitCode =
        new PdfExtractorApplication(new PrintStream(combineOut), new PrintStream(combineErr))
            .run(
                new String[] {
                  "combine",
                  inputDir.toString(),
                  "--output-file",
                  combinedMarkdown.toString(),
                  "--ocr-mode",
                  "off"
                });

    assertThat(combineExitCode).isZero();
    assertThat(combineErr.toString()).isBlank();
    assertThat(combinedMarkdown).exists();
    String bundleContent = Files.readString(combinedMarkdown);
    assertThat(bundleContent).contains("<<<FILE>>>");
    assertThat(bundleContent).contains("PATH: alpha.md");
    assertThat(bundleContent).contains("PATH: nested/beta.md");
    assertThat(bundleContent).contains("# Alpha Document");
    assertThat(bundleContent).contains("# Beta Document");

    Path restoredDir = tempDir.resolve("restored");
    ByteArrayOutputStream splitOut = new ByteArrayOutputStream();
    ByteArrayOutputStream splitErr = new ByteArrayOutputStream();
    int splitExitCode =
        new PdfExtractorApplication(new PrintStream(splitOut), new PrintStream(splitErr))
            .run(
                new String[] {
                  "split",
                  combinedMarkdown.toString(),
                  "--output-dir",
                  restoredDir.toString()
                });

    assertThat(splitExitCode).isZero();
    assertThat(splitErr.toString()).isBlank();
    assertThat(restoredDir.resolve("alpha.md")).exists();
    assertThat(restoredDir.resolve("nested/beta.md")).exists();
    assertThat(Files.readString(restoredDir.resolve("alpha.md")))
        .contains("# Alpha Document")
        .contains("Alpha body paragraph for bundle test.");
    assertThat(Files.readString(restoredDir.resolve("nested/beta.md")))
        .contains("# Beta Document")
        .contains("Beta body paragraph for bundle test.");
  }

  private static void createSamplePdf(Path target) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      document.addPage(page);

      try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
        writeLine(contentStream, PDType1Font.HELVETICA_BOLD, 20, 72, 760, "Sample Document");
        writeLine(
            contentStream,
            PDType1Font.HELVETICA,
            12,
            72,
            724,
            "This paragraph should stay together in the extracted markdown output.");
        writeLine(contentStream, PDType1Font.HELVETICA_BOLD, 12, 72, 680, "Example link");

        writeLine(contentStream, PDType1Font.HELVETICA_BOLD, 12, 72, 632, "Quarter");
        writeLine(contentStream, PDType1Font.HELVETICA_BOLD, 12, 220, 632, "Revenue");
        writeLine(contentStream, PDType1Font.HELVETICA, 12, 72, 612, "Q1");
        writeLine(contentStream, PDType1Font.HELVETICA, 12, 220, 612, "120");
      }

      PDAnnotationLink link = new PDAnnotationLink();
      link.setRectangle(new PDRectangle(70, 674, 110, 16));
      PDBorderStyleDictionary border = new PDBorderStyleDictionary();
      border.setWidth(0);
      link.setBorderStyle(border);
      PDActionURI action = new PDActionURI();
      action.setURI("https://example.com/report");
      link.setAction(action);
      page.getAnnotations().add(link);

      document.save(target.toFile());
    }
  }

  private static void createPdf(Path target, String title, List<String> lines) throws IOException {
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(PDRectangle.A4);
      document.addPage(page);

      try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
        int y = 760;
        boolean firstTableRow = true;
        writeLine(contentStream, PDType1Font.HELVETICA_BOLD, 20, 72, y, title);
        y -= 36;

        for (String line : lines) {
          if (line.startsWith("LINK::")) {
            String[] parts = line.split("::", 3);
            writeLine(contentStream, PDType1Font.HELVETICA_BOLD, 12, 72, y, parts[1]);
            y -= 24;
            continue;
          }
          if (line.startsWith("TABLE::")) {
            String[] parts = line.split("::", 3);
            boolean header = firstTableRow;
            writeLine(
                contentStream,
                header ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA,
                12,
                72,
                y,
                parts[1]);
            writeLine(
                contentStream,
                header ? PDType1Font.HELVETICA_BOLD : PDType1Font.HELVETICA,
                12,
                220,
                y,
                parts[2]);
            firstTableRow = false;
            y -= 20;
            continue;
          }
          writeLine(contentStream, PDType1Font.HELVETICA, 12, 72, y, line);
          y -= 24;
        }
      }

      if (lines.stream().anyMatch(line -> line.startsWith("LINK::"))) {
        PDAnnotationLink link = new PDAnnotationLink();
        link.setRectangle(new PDRectangle(70, 674, 110, 16));
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);
        PDActionURI action = new PDActionURI();
        action.setURI("https://example.com/report");
        link.setAction(action);
        page.getAnnotations().add(link);
      }

      document.save(target.toFile());
    }
  }

  private static void writeLine(
      PDPageContentStream contentStream, PDType1Font font, int fontSize, int x, int y, String text)
      throws IOException {
    contentStream.beginText();
    contentStream.setFont(font, fontSize);
    contentStream.newLineAtOffset(x, y);
    contentStream.showText(text);
    contentStream.endText();
  }
}
