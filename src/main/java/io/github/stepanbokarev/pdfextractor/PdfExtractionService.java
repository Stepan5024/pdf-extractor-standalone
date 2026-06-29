package io.github.stepanbokarev.pdfextractor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

/** Converts PDF documents into readable Markdown or TXT. */
final class PdfExtractionService {

  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
  private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([,.;:%!?])");
  private static final Comparator<TextFragment> FRAGMENT_ORDER =
      Comparator.comparingDouble(TextFragment::top).thenComparingDouble(TextFragment::left);

  PdfExtractionOutcome extract(PdfExtractionOptions options)
      throws IOException, InterruptedException {
    try (PDDocument document = PDDocument.load(options.inputFile().toFile())) {
      PDFRenderer renderer = new PDFRenderer(document);
      PdfOcrEngine ocrEngine = new PdfOcrEngine();
      List<String> pages = new ArrayList<>();
      int ocrPages = 0;

      for (int pageIndex = 0; pageIndex < document.getNumberOfPages(); pageIndex++) {
        PageRenderResult pageResult = renderPage(document, renderer, pageIndex, options, ocrEngine);
        if (!pageResult.content().isBlank()) {
          pages.add(pageResult.content().trim());
        }
        if (pageResult.ocrUsed()) {
          ocrPages++;
        }
      }

      String separator = options.fileFormat() == PdfFileFormat.md ? "\n\n---\n\n" : "\n\n";
      String content = String.join(separator, pages).trim() + "\n";
      Files.createDirectories(options.outputFile().getParent());
      Files.writeString(options.outputFile(), content, StandardCharsets.UTF_8);
      return new PdfExtractionOutcome(
          options.outputFile(),
          options.fileFormat(),
          document.getNumberOfPages(),
          ocrPages,
          ocrEngine.isAvailable());
    }
  }

  private PageRenderResult renderPage(
      PDDocument document,
      PDFRenderer renderer,
      int pageIndex,
      PdfExtractionOptions options,
      PdfOcrEngine ocrEngine)
      throws IOException, InterruptedException {
    int pageNumber = pageIndex + 1;
    FragmentCollectingStripper stripper = new FragmentCollectingStripper();
    List<TextFragment> fragments = stripper.extractPage(document, pageNumber);
    Map<TextFragment, String> links = resolveLinks(document.getPage(pageIndex), fragments);
    List<LineData> lines = groupLines(fragments, links);

    if (options.ocrMode() == OcrMode.always && !ocrEngine.canOcr(options.ocrLanguage())) {
      throw new IOException(
          "OCR mode 'always' was requested but no local OCR backend can serve language expression '"
              + options.ocrLanguage()
              + "'");
    }

    if (shouldUseOcr(lines, options.ocrMode()) && ocrEngine.canOcr(options.ocrLanguage())) {
      BufferedImage image = renderer.renderImageWithDPI(pageIndex, options.ocrDpi());
      String ocrText = normalizeOcr(ocrEngine.ocr(image, options.ocrLanguage()));
      if (!ocrText.isBlank()) {
        return new PageRenderResult(renderOcrPage(pageNumber, ocrText, options.fileFormat()), true);
      }
    }

    return new PageRenderResult(renderNativePage(pageNumber, lines, options.fileFormat()), false);
  }

  private static boolean shouldUseOcr(List<LineData> lines, OcrMode ocrMode) {
    if (ocrMode == OcrMode.off) {
      return false;
    }
    if (ocrMode == OcrMode.always) {
      return true;
    }
    String nativeText =
        lines.stream()
            .map(LineData::plainText)
            .reduce("", (left, right) -> left + " " + right)
            .trim();
    return nativeText.length() < 80;
  }

  private static String renderOcrPage(int pageNumber, String text, PdfFileFormat fileFormat) {
    if (fileFormat == PdfFileFormat.md) {
      return "## Page %d\n\n%s".formatted(pageNumber, text.trim());
    }
    return text.trim();
  }

  private static String renderNativePage(
      int pageNumber, List<LineData> lines, PdfFileFormat fileFormat) {
    if (lines.isEmpty()) {
      return fileFormat == PdfFileFormat.md ? "## Page %d".formatted(pageNumber) : "";
    }

    double bodyFont =
        lines.stream()
            .flatMap(line -> line.fragments().stream())
            .mapToDouble(TextFragment::fontSize)
            .sorted()
            .skip(Math.max(0, lines.stream().mapToInt(line -> line.fragments().size()).sum() / 4))
            .findFirst()
            .orElse(12.0d);

    Map<Integer, TableSpan> tables = detectTables(lines);
    StringBuilder rendered = new StringBuilder();
    StringBuilder paragraph = new StringBuilder();
    int index = 0;

    if (fileFormat == PdfFileFormat.md) {
      rendered.append("## Page ").append(pageNumber).append("\n\n");
    }

    while (index < lines.size()) {
      TableSpan tableSpan = tables.get(index);
      if (tableSpan != null) {
        flushParagraph(rendered, paragraph);
        rendered.append(renderMarkdownTable(tableSpan.rows())).append("\n\n");
        index = tableSpan.endExclusive();
        continue;
      }

      LineData line = lines.get(index);
      String markdownText = line.markdownText();
      if (markdownText.isBlank()) {
        flushParagraph(rendered, paragraph);
        index++;
        continue;
      }

      if (fileFormat == PdfFileFormat.md && isHeading(line, bodyFont)) {
        flushParagraph(rendered, paragraph);
        rendered.append(headingPrefix(line, bodyFont)).append(markdownText).append("\n\n");
        index++;
        continue;
      }

      if (isBullet(line)) {
        flushParagraph(rendered, paragraph);
        String bulletText = markdownText.replaceFirst("^[\\u2022\\-–]\\s*", "");
        rendered.append("- ").append(bulletText).append('\n');
        index++;
        continue;
      }

      if (paragraph.length() > 0) {
        paragraph.append(' ');
      }
      paragraph.append(markdownText);

      if (hasParagraphBreak(lines, index, bodyFont)) {
        flushParagraph(rendered, paragraph);
      }
      index++;
    }

    flushParagraph(rendered, paragraph);
    return rendered.toString().trim();
  }

  private static boolean isHeading(LineData line, double bodyFont) {
    return line.maxFontSize() >= bodyFont * 1.35d && line.fragments().size() <= 12;
  }

  private static String headingPrefix(LineData line, double bodyFont) {
    if (line.maxFontSize() >= bodyFont * 1.9d) {
      return "# ";
    }
    if (line.maxFontSize() >= bodyFont * 1.55d) {
      return "## ";
    }
    return "### ";
  }

  private static boolean isBullet(LineData line) {
    return line.plainText().startsWith("•")
        || line.plainText().startsWith("- ")
        || line.plainText().startsWith("– ");
  }

  private static boolean hasParagraphBreak(List<LineData> lines, int index, double bodyFont) {
    if (index + 1 >= lines.size()) {
      return true;
    }
    LineData current = lines.get(index);
    LineData next = lines.get(index + 1);
    double gap = next.top() - current.top();
    return gap > Math.max(bodyFont * 1.7d, current.maxHeight() * 1.5d)
        || current.plainText().endsWith(":");
  }

  private static void flushParagraph(StringBuilder rendered, StringBuilder paragraph) {
    if (paragraph.length() == 0) {
      return;
    }
    rendered.append(paragraph).append("\n\n");
    paragraph.setLength(0);
  }

  private static String renderMarkdownTable(List<List<String>> rows) {
    if (rows.isEmpty()) {
      return "";
    }
    int columns = rows.stream().mapToInt(List::size).max().orElse(0);
    List<List<String>> normalized = new ArrayList<>();
    for (List<String> row : rows) {
      List<String> padded = new ArrayList<>(row);
      while (padded.size() < columns) {
        padded.add("");
      }
      normalized.add(padded);
    }

    StringBuilder builder = new StringBuilder();
    appendTableRow(builder, normalized.get(0));
    appendTableSeparator(builder, columns);
    for (int rowIndex = 1; rowIndex < normalized.size(); rowIndex++) {
      appendTableRow(builder, normalized.get(rowIndex));
    }
    return builder.toString().trim();
  }

  private static void appendTableRow(StringBuilder builder, List<String> row) {
    builder.append('|');
    for (String cell : row) {
      builder.append(' ').append(cell.trim()).append(' ').append('|');
    }
    builder.append('\n');
  }

  private static void appendTableSeparator(StringBuilder builder, int columns) {
    builder.append('|');
    for (int columnIndex = 0; columnIndex < columns; columnIndex++) {
      builder.append(" --- |");
    }
    builder.append('\n');
  }

  private static Map<Integer, TableSpan> detectTables(List<LineData> lines) {
    Map<Integer, TableSpan> tables = new HashMap<>();
    int index = 0;
    while (index < lines.size() - 1) {
      LineData header = lines.get(index);
      if (header.fragments().size() < 2 || header.fragments().size() > 6) {
        index++;
        continue;
      }

      List<LineData> candidates = new ArrayList<>();
      candidates.add(header);
      int nextIndex = index + 1;
      while (nextIndex < lines.size()
          && lines.get(nextIndex).fragments().size() >= 2
          && similarColumns(header, lines.get(nextIndex))) {
        candidates.add(lines.get(nextIndex));
        nextIndex++;
      }

      if (candidates.size() >= 2) {
        if (!looksLikeTable(candidates)) {
          index++;
          continue;
        }
        List<Double> anchors = header.fragments().stream().map(TextFragment::left).toList();
        List<List<String>> rows = new ArrayList<>();
        for (LineData candidate : candidates) {
          rows.add(assignCells(candidate.fragments(), anchors));
        }
        tables.put(index, new TableSpan(nextIndex, rows));
        index = nextIndex;
        continue;
      }
      index++;
    }
    return tables;
  }

  private static boolean looksLikeTable(List<LineData> candidates) {
    if (candidates.size() >= 3) {
      return true;
    }
    LineData header = candidates.get(0);
    LineData dataRow = candidates.get(1);
    return hasBoldHeader(header) && containsStructuredData(dataRow);
  }

  private static boolean hasBoldHeader(LineData line) {
    long boldFragments = line.fragments().stream().filter(TextFragment::bold).count();
    return boldFragments >= Math.max(1, line.fragments().size() / 2);
  }

  private static boolean containsStructuredData(LineData line) {
    return line.fragments().stream()
        .map(TextFragment::text)
        .anyMatch(
            token ->
                token.chars().anyMatch(Character::isDigit)
                    || token.contains("%")
                    || token.contains("$")
                    || token.contains("₽")
                    || token.contains("€"));
  }

  private static boolean similarColumns(LineData reference, LineData candidate) {
    if (reference.fragments().size() != candidate.fragments().size()) {
      return false;
    }
    for (int index = 0; index < reference.fragments().size(); index++) {
      if (Math.abs(
              reference.fragments().get(index).left() - candidate.fragments().get(index).left())
          > 36.0d) {
        return false;
      }
    }
    return true;
  }

  private static List<String> assignCells(List<TextFragment> fragments, List<Double> anchors) {
    Map<Integer, StringBuilder> cells = new LinkedHashMap<>();
    for (int index = 0; index < anchors.size(); index++) {
      cells.put(index, new StringBuilder());
    }

    int anchorIndex = 0;
    for (TextFragment fragment : fragments) {
      while (anchorIndex + 1 < anchors.size()
          && Math.abs(fragment.left() - anchors.get(anchorIndex + 1))
              < Math.abs(fragment.left() - anchors.get(anchorIndex))) {
        anchorIndex++;
      }
      StringBuilder cell = cells.get(anchorIndex);
      if (cell.length() > 0) {
        cell.append(' ');
      }
      cell.append(fragment.text());
    }

    return cells.values().stream().map(StringBuilder::toString).map(String::trim).toList();
  }

  private static List<LineData> groupLines(
      List<TextFragment> fragments, Map<TextFragment, String> linksByFragment) {
    List<TextFragment> ordered = new ArrayList<>(fragments);
    ordered.sort(FRAGMENT_ORDER);
    List<LineData> lines = new ArrayList<>();
    List<TextFragment> current = new ArrayList<>();
    double currentTop = Double.NaN;

    for (TextFragment fragment : ordered) {
      if (current.isEmpty()) {
        current.add(fragment);
        currentTop = fragment.top();
        continue;
      }
      double tolerance = Math.max(3.0d, fragment.height() * 0.45d);
      if (Math.abs(fragment.top() - currentTop) <= tolerance) {
        current.add(fragment);
      } else {
        lines.add(asLine(current, linksByFragment));
        current = new ArrayList<>();
        current.add(fragment);
        currentTop = fragment.top();
      }
    }
    if (!current.isEmpty()) {
      lines.add(asLine(current, linksByFragment));
    }
    return lines;
  }

  private static LineData asLine(
      List<TextFragment> fragments, Map<TextFragment, String> linksByFragment) {
    List<TextFragment> ordered =
        fragments.stream().sorted(Comparator.comparingDouble(TextFragment::left)).toList();
    List<String> plainTokens = new ArrayList<>();
    List<String> markdownTokens = new ArrayList<>();
    StringBuilder linkedText = new StringBuilder();
    String currentLink = null;
    double maxFont = 0.0d;
    double maxHeight = 0.0d;
    double top = ordered.get(0).top();

    for (TextFragment fragment : ordered) {
      plainTokens.add(fragment.text());
      String link = linksByFragment.get(fragment);
      if (link == null) {
        if (currentLink != null) {
          markdownTokens.add("[%s](%s)".formatted(linkedText, currentLink));
          currentLink = null;
          linkedText.setLength(0);
        }
        markdownTokens.add(fragment.text());
      } else if (currentLink == null) {
        currentLink = link;
        linkedText.append(fragment.text());
      } else if (currentLink.equals(link)) {
        linkedText.append(' ').append(fragment.text());
      } else {
        markdownTokens.add("[%s](%s)".formatted(linkedText, currentLink));
        currentLink = link;
        linkedText.setLength(0);
        linkedText.append(fragment.text());
      }
      maxFont = Math.max(maxFont, fragment.fontSize());
      maxHeight = Math.max(maxHeight, fragment.height());
    }

    if (currentLink != null) {
      markdownTokens.add("[%s](%s)".formatted(linkedText, currentLink));
    }

    String plainText = normalizeInlineSpacing(String.join(" ", plainTokens));
    String markdownText = normalizeInlineSpacing(String.join(" ", markdownTokens));

    return new LineData(ordered, plainText, markdownText, top, maxFont, maxHeight);
  }

  private static String normalizeInlineSpacing(String text) {
    return SPACE_BEFORE_PUNCTUATION
        .matcher(MULTI_SPACE.matcher(text).replaceAll(" ").trim())
        .replaceAll("$1");
  }

  private static Map<TextFragment, String> resolveLinks(PDPage page, List<TextFragment> fragments)
      throws IOException {
    Map<TextFragment, String> links = new HashMap<>();
    float pageHeight = page.getMediaBox().getHeight();
    for (PDAnnotation annotation : page.getAnnotations()) {
      if (!(annotation instanceof PDAnnotationLink linkAnnotation)) {
        continue;
      }
      String uri = resolveUri(linkAnnotation.getAction());
      if (uri == null || linkAnnotation.getRectangle() == null) {
        continue;
      }
      PDRectangle rect = linkAnnotation.getRectangle();
      double rectLeft = rect.getLowerLeftX();
      double rectRight = rect.getUpperRightX();
      double rectTop = pageHeight - rect.getUpperRightY();
      double rectBottom = pageHeight - rect.getLowerLeftY();

      for (TextFragment fragment : fragments) {
        double fragmentRight = fragment.left() + fragment.width();
        double fragmentBottom = fragment.top() + fragment.height();
        boolean overlapsHorizontally = fragment.left() <= rectRight && fragmentRight >= rectLeft;
        boolean overlapsVertically = fragment.top() <= rectBottom && fragmentBottom >= rectTop;
        if (overlapsHorizontally && overlapsVertically) {
          links.put(fragment, uri);
        }
      }
    }
    return links;
  }

  private static String resolveUri(PDAction action) {
    if (action instanceof PDActionURI uriAction) {
      return uriAction.getURI();
    }
    return null;
  }

  private static String normalizeOcr(String text) {
    return text.replace("\r", "")
        .replaceAll("(?m)^Estimating resolution.*$", "")
        .replaceAll("(?m)^OSD:.*$", "")
        .replaceAll("[ \t]+\n", "\n")
        .replaceAll("\n{3,}", "\n\n")
        .trim();
  }

  private record PageRenderResult(String content, boolean ocrUsed) {}

  private record TableSpan(int endExclusive, List<List<String>> rows) {}

  private record LineData(
      List<TextFragment> fragments,
      String plainText,
      String markdownText,
      double top,
      double maxFontSize,
      double maxHeight) {}

  private record TextFragment(
      String text,
      double left,
      double top,
      double width,
      double height,
      double fontSize,
      boolean bold) {}

  private static final class FragmentCollectingStripper extends PDFTextStripper {

    private final List<TextFragment> fragments = new ArrayList<>();

    private FragmentCollectingStripper() throws IOException {
      setSortByPosition(true);
      setAddMoreFormatting(true);
    }

    private List<TextFragment> extractPage(PDDocument document, int pageNumber) throws IOException {
      fragments.clear();
      setStartPage(pageNumber);
      setEndPage(pageNumber);
      getText(document);
      return List.copyOf(fragments);
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
      super.writeString(text, positions);
      if (positions.isEmpty()) {
        return;
      }

      List<TextPosition> chunk = new ArrayList<>();
      for (TextPosition position : positions) {
        String unicode = position.getUnicode();
        if (unicode == null || unicode.isBlank()) {
          flushChunk(chunk);
          continue;
        }
        if (!chunk.isEmpty() && shouldSplit(chunk.get(chunk.size() - 1), position)) {
          flushChunk(chunk);
        }
        chunk.add(position);
      }
      flushChunk(chunk);
    }

    private void flushChunk(List<TextPosition> chunk) {
      if (chunk.isEmpty()) {
        return;
      }
      StringBuilder builder = new StringBuilder();
      double left = chunk.get(0).getXDirAdj();
      double right = left;
      double top = chunk.get(0).getYDirAdj();
      double height = 0.0d;
      double fontSize = 0.0d;
      boolean bold = false;

      for (TextPosition position : chunk) {
        builder.append(position.getUnicode());
        right = Math.max(right, position.getXDirAdj() + position.getWidthDirAdj());
        height = Math.max(height, position.getHeightDir());
        fontSize = Math.max(fontSize, position.getFontSizeInPt());
        String fontName = position.getFont().getName();
        if (fontName.contains("Bold") || fontName.contains("Semibold")) {
          bold = true;
        }
      }

      String normalized = MULTI_SPACE.matcher(builder).replaceAll(" ").trim();
      if (!normalized.isBlank()) {
        fragments.add(
            new TextFragment(normalized, left, top, right - left, height, fontSize, bold));
      }
      chunk.clear();
    }

    private static boolean shouldSplit(TextPosition previous, TextPosition current) {
      double previousRight = previous.getXDirAdj() + previous.getWidthDirAdj();
      double gap = current.getXDirAdj() - previousRight;
      return gap > Math.max(6.0d, previous.getWidthOfSpace() * 0.8d);
    }
  }
}
