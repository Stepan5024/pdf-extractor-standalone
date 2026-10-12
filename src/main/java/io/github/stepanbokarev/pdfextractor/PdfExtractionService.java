package io.github.stepanbokarev.pdfextractor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
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
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.encoding.GlyphList;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.util.Vector;

/** Converts PDF documents into readable Markdown or TXT. */
final class PdfExtractionService {

  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");
  private static final Pattern SPACE_BEFORE_PUNCTUATION = Pattern.compile("\\s+([,.;:%!?])");
  private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d{1,3}$");
  private static final Comparator<TextFragment> FRAGMENT_ORDER =
      Comparator.comparingDouble(TextFragment::top).thenComparingDouble(TextFragment::left);
  private static final Comparator<LineData> LINE_ORDER =
      Comparator.comparingDouble(LineData::top).thenComparingDouble(LineData::left);

  PdfExtractionOutcome extract(PdfExtractionOptions options)
      throws IOException, InterruptedException {
    RenderedDocument renderedDocument = render(options);
    Files.createDirectories(options.outputFile().getParent());
    Files.writeString(options.outputFile(), renderedDocument.content(), StandardCharsets.UTF_8);
    return new PdfExtractionOutcome(
        options.outputFile(),
        options.fileFormat(),
        renderedDocument.pageCount(),
        renderedDocument.ocrPages(),
        renderedDocument.ocrAvailable());
  }

  RenderedDocument render(PdfExtractionOptions options) throws IOException, InterruptedException {
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
      return new RenderedDocument(
          content, document.getNumberOfPages(), ocrPages, ocrEngine.isAvailable());
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
    PDPage page = document.getPage(pageIndex);
    FragmentCollectingStripper stripper = new FragmentCollectingStripper();
    List<TextFragment> fragments = stripper.extractPage(document, pageNumber);
    int unmappedGlyphs = stripper.unmappedGlyphs;
    Map<TextFragment, String> links = resolveLinks(page, fragments);
    List<LineData> lines = buildReadingOrder(page, fragments, links);

    if (unmappedGlyphs > 0 && options.ocrMode() == OcrMode.off) {
      throw new IOException(
          "Page "
              + pageNumber
              + " contains "
              + unmappedGlyphs
              + " glyph(s) without a Unicode mapping. OCR is required; use --ocr-mode auto.");
    }
    if (unmappedGlyphs > 0 && !ocrEngine.canOcr(options.ocrLanguage())) {
      throw new IOException(
          "Page "
              + pageNumber
              + " contains "
              + unmappedGlyphs
              + " glyph(s) without a Unicode mapping. No local OCR backend can serve '"
              + options.ocrLanguage()
              + "'. Install Tesseract with the required language data.");
    }
    if (options.ocrMode() == OcrMode.always && !ocrEngine.canOcr(options.ocrLanguage())) {
      throw new IOException(
          "OCR mode 'always' was requested but no local OCR backend can serve language expression '"
              + options.ocrLanguage()
              + "'");
    }

    if (shouldUseOcr(lines, options.ocrMode(), unmappedGlyphs)
        && ocrEngine.canOcr(options.ocrLanguage())) {
      String ocrText;
      try {
        BufferedImage image = renderer.renderImageWithDPI(pageIndex, options.ocrDpi());
        ocrText = normalizeOcr(ocrEngine.ocr(image, options.ocrLanguage()));
      } catch (IOException exception) {
        if (unmappedGlyphs > 0) {
          throw new IOException(
              "Page "
                  + pageNumber
                  + " has missing Unicode mappings; OCR recovery failed: "
                  + exception.getMessage(),
              exception);
        }
        throw exception;
      }
      if (!ocrText.isBlank()) {
        return new PageRenderResult(renderOcrPage(pageNumber, ocrText, options.fileFormat()), true);
      }
      if (unmappedGlyphs > 0) {
        throw new IOException(
            "Page "
                + pageNumber
                + " has missing Unicode mappings and OCR returned no text; refusing incomplete"
                + " extraction.");
      }
    }

    return new PageRenderResult(
        renderNativePage(pageNumber, lines, page.getMediaBox(), options.fileFormat()), false);
  }

  private static boolean shouldUseOcr(List<LineData> lines, OcrMode ocrMode, int unmappedGlyphs) {
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
    return unmappedGlyphs > 0 || nativeText.length() < 80;
  }

  private static String renderOcrPage(int pageNumber, String text, PdfFileFormat fileFormat) {
    if (fileFormat == PdfFileFormat.md) {
      return "## Page %d\n\n%s".formatted(pageNumber, text.trim());
    }
    return text.trim();
  }

  private static String renderNativePage(
      int pageNumber, List<LineData> lines, PDRectangle pageBox, PdfFileFormat fileFormat) {
    if (lines.isEmpty()) {
      return fileFormat == PdfFileFormat.md ? "## Page %d".formatted(pageNumber) : "";
    }

    List<LineData> filteredLines =
        lines.stream()
            .filter(line -> !isPageNumberNoise(line, pageBox))
            .filter(line -> !isDecorativeSymbolLine(line))
            .filter(line -> !line.markdownText().isBlank())
            .toList();
    if (filteredLines.isEmpty()) {
      return fileFormat == PdfFileFormat.md ? "## Page %d".formatted(pageNumber) : "";
    }

    double bodyFont = dominantBodyFont(filteredLines);
    filteredLines = reorderRightInsets(filteredLines, pageBox.getWidth(), bodyFont);
    filteredLines = reorderFeatureCardClusters(filteredLines, pageBox.getWidth(), bodyFont);
    filteredLines = reorderOutOfOrderTrailingBlocks(filteredLines, bodyFont);

    Map<Integer, TableSpan> tables = detectTables(filteredLines);
    StringBuilder rendered = new StringBuilder();
    StringBuilder paragraph = new StringBuilder();
    int index = 0;

    if (fileFormat == PdfFileFormat.md) {
      rendered.append("## Page ").append(pageNumber).append("\n\n");
    }

    while (index < filteredLines.size()) {
      TableSpan tableSpan = tables.get(index);
      if (tableSpan != null) {
        flushParagraph(rendered, paragraph);
        rendered.append(renderMarkdownTable(tableSpan.rows())).append("\n\n");
        index = tableSpan.endExclusive();
        continue;
      }

      LineData line = filteredLines.get(index);
      String markdownText = line.markdownText();
      if (markdownText.isBlank()) {
        flushParagraph(rendered, paragraph);
        index++;
        continue;
      }

      if (fileFormat == PdfFileFormat.md && isHeading(line, bodyFont)) {
        flushParagraph(rendered, paragraph);
        HeadingSpan headingSpan = consumeHeading(filteredLines, index, bodyFont);
        rendered
            .append(headingPrefix(headingSpan.firstLine(), bodyFont))
            .append(headingSpan.text())
            .append("\n\n");
        index = headingSpan.nextIndex();
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
        appendParagraphSeparator(paragraph, line);
      }
      paragraph.append(markdownText);

      if (hasParagraphBreak(filteredLines, index, bodyFont, pageBox.getWidth())) {
        flushParagraph(rendered, paragraph);
      }
      index++;
    }

    flushParagraph(rendered, paragraph);
    return rendered.toString().trim();
  }

  private static boolean isHeading(LineData line, double bodyFont) {
    boolean prominentHeading =
        line.maxFontSize() >= bodyFont * 1.32d
            && line.fragments().size() <= 18
            && (line.markdownText().length() <= 120 || line.boldFraction() >= 0.45d);
    boolean shortBoldLabel = line.maxFontSize() >= bodyFont * 1.1d && isShortBoldLabel(line);
    boolean cardSubtitle = isCardSubtitle(line);
    boolean compactUppercaseLabel = isCompactUppercaseLabel(line);
    return prominentHeading || shortBoldLabel || cardSubtitle || compactUppercaseLabel;
  }

  private static boolean isShortBoldLabel(LineData line) {
    return line.boldFraction() >= 0.8d
        && line.fragments().size() <= 4
        && line.markdownText().length() <= 32;
  }

  private static boolean isCompactUppercaseLabel(LineData line) {
    return line.fragments().size() <= 4
        && line.markdownText().length() <= 32
        && hasMostlyUppercaseLetters(line.plainText());
  }

  private static boolean isCardSubtitle(LineData line) {
    return line.boldFraction() >= 0.8d
        && line.fragments().size() <= 4
        && line.markdownText().length() <= 48
        && !endsWithStrongSentenceBoundary(line.plainText())
        && !hasMostlyUppercaseLetters(line.plainText());
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

  private static boolean hasParagraphBreak(
      List<LineData> lines, int index, double bodyFont, double pageWidth) {
    if (index + 1 >= lines.size()) {
      return true;
    }
    LineData current = lines.get(index);
    LineData next = lines.get(index + 1);
    double gap = next.top() - current.top();
    if (gap > Math.max(bodyFont * 1.7d, current.maxHeight() * 1.45d)) {
      return true;
    }
    if (current.plainText().endsWith(":")) {
      return true;
    }
    if (isBullet(next)) {
      return true;
    }
    if (Math.abs(current.left() - next.left()) > Math.max(10.0d, bodyFont * 0.8d)) {
      return true;
    }
    return current.width() < pageWidth * 0.58d
        && endsWithStrongSentenceBoundary(current.plainText());
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
    LineData header = candidates.get(0);
    long structuredRows = countStructuredRows(candidates);
    if (!compactCells(candidates) || !hasStructuredRowMajority(candidates, structuredRows)) {
      return false;
    }
    if (hasBoldHeader(header) && structuredRows >= 1) {
      return true;
    }
    return structuredRows >= 2;
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

  private static boolean compactCells(List<LineData> candidates) {
    return candidates.stream()
        .flatMap(line -> line.fragments().stream())
        .map(TextFragment::text)
        .allMatch(token -> token.length() <= 28);
  }

  private static long countStructuredRows(List<LineData> lines) {
    return lines.stream().skip(1).filter(PdfExtractionService::containsStructuredData).count();
  }

  private static boolean hasStructuredRowMajority(List<LineData> lines, long structuredRows) {
    int dataRowCount = Math.max(0, lines.size() - 1);
    return dataRowCount > 0 && structuredRows * 2L > dataRowCount;
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

  private static List<LineData> buildReadingOrder(
      PDPage page, List<TextFragment> fragments, Map<TextFragment, String> linksByFragment) {
    List<List<TextFragment>> rawLines = groupRawLines(fragments);
    List<List<LineData>> segmentedRows = segmentRows(rawLines, linksByFragment, page.getMediaBox());
    List<LineData> standaloneLines = new ArrayList<>();
    List<BlockData> seededBlocks = new ArrayList<>();
    int index = 0;
    while (index < segmentedRows.size()) {
      TablePreservationSpan tableSpan = detectPreservedTable(segmentedRows, index, linksByFragment);
      if (tableSpan != null) {
        standaloneLines.addAll(tableSpan.rows());
        index = tableSpan.endExclusive();
        continue;
      }
      ColumnCardSpan columnCards =
          detectColumnCards(segmentedRows, index, page.getMediaBox().getWidth());
      if (columnCards != null) {
        seededBlocks.addAll(columnCards.blocks());
        index = columnCards.endExclusive();
        continue;
      }
      standaloneLines.addAll(segmentedRows.get(index));
      index++;
    }
    List<BlockData> blocks = new ArrayList<>(seededBlocks);
    blocks.addAll(groupIntoBlocks(standaloneLines, page.getMediaBox().getWidth()));
    List<BlockData> orderedBlocks = orderBlocksByLayout(blocks, page.getMediaBox(), 0);
    List<LineData> orderedLines = new ArrayList<>();
    for (BlockData block : orderedBlocks) {
      orderedLines.addAll(block.lines());
    }
    return orderedLines;
  }

  private static List<List<TextFragment>> groupRawLines(List<TextFragment> fragments) {
    List<TextFragment> ordered = new ArrayList<>(fragments);
    ordered.sort(FRAGMENT_ORDER);
    List<List<TextFragment>> lines = new ArrayList<>();
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
        lines.add(List.copyOf(current));
        current = new ArrayList<>();
        current.add(fragment);
        currentTop = fragment.top();
      }
    }
    if (!current.isEmpty()) {
      lines.add(List.copyOf(current));
    }
    return lines;
  }

  private static List<List<LineData>> segmentRows(
      List<List<TextFragment>> rawLines,
      Map<TextFragment, String> linksByFragment,
      PDRectangle pageBox) {
    List<List<LineData>> segmentedRows = new ArrayList<>();
    double pageWidth = pageBox.getWidth();
    for (List<TextFragment> rawLine : rawLines) {
      List<TextFragment> ordered =
          rawLine.stream().sorted(Comparator.comparingDouble(TextFragment::left)).toList();
      List<TextFragment> current = new ArrayList<>();
      List<LineData> rowSegments = new ArrayList<>();
      for (int index = 0; index < ordered.size(); index++) {
        TextFragment fragment = ordered.get(index);
        if (current.isEmpty()) {
          current.add(fragment);
          continue;
        }
        TextFragment previous = current.get(current.size() - 1);
        if (shouldSplitSegment(previous, fragment, pageWidth)) {
          rowSegments.add(asLine(current, linksByFragment));
          current = new ArrayList<>();
        }
        current.add(fragment);
      }
      if (!current.isEmpty()) {
        rowSegments.add(asLine(current, linksByFragment));
      }
      segmentedRows.add(rowSegments);
    }
    return segmentedRows;
  }

  private static boolean shouldSplitSegment(
      TextFragment previous, TextFragment current, double pageWidth) {
    double gap = current.left() - previous.right();
    double threshold = Math.max(12.0d, Math.min(pageWidth * 0.03d, 20.0d));
    return gap > threshold;
  }

  private static TablePreservationSpan detectPreservedTable(
      List<List<LineData>> segmentedRows,
      int startIndex,
      Map<TextFragment, String> linksByFragment) {
    List<LineData> headerSegments = segmentedRows.get(startIndex);
    if (headerSegments.size() < 2 || headerSegments.size() > 6) {
      return null;
    }

    List<List<LineData>> candidateRows = new ArrayList<>();
    candidateRows.add(headerSegments);
    int index = startIndex + 1;
    while (index < segmentedRows.size()
        && segmentedRows.get(index).size() == headerSegments.size()
        && similarAnchors(headerSegments, segmentedRows.get(index))) {
      candidateRows.add(segmentedRows.get(index));
      index++;
    }

    if (candidateRows.size() < 2) {
      return null;
    }

    List<LineData> combinedRows =
        candidateRows.stream().map(rows -> combineSegments(rows, linksByFragment)).toList();
    if (!shouldPreserveAsTable(combinedRows)) {
      return null;
    }
    return new TablePreservationSpan(index, combinedRows);
  }

  private static boolean shouldPreserveAsTable(List<LineData> combinedRows) {
    if (combinedRows.size() < 2) {
      return false;
    }
    LineData header = combinedRows.get(0);
    long structuredRows = countStructuredRows(combinedRows);
    return hasBoldHeader(header)
        && structuredRows >= 1
        && compactCells(combinedRows)
        && hasStructuredRowMajority(combinedRows, structuredRows);
  }

  private static ColumnCardSpan detectColumnCards(
      List<List<LineData>> segmentedRows, int startIndex, double pageWidth) {
    List<LineData> headerSegments = segmentedRows.get(startIndex);
    if (!isColumnCardHeader(headerSegments)) {
      return null;
    }

    List<Double> centers = headerSegments.stream().map(LineData::centerX).toList();
    List<List<LineData>> columns = new ArrayList<>();
    for (LineData headerSegment : headerSegments) {
      columns.add(new ArrayList<>(List.of(headerSegment)));
    }

    int index = startIndex + 1;
    int matchedRowCount = 0;
    double previousBottom =
        headerSegments.stream().mapToDouble(LineData::bottom).max().orElse(0.0d);
    while (index < segmentedRows.size()) {
      List<LineData> rowSegments = segmentedRows.get(index);
      if (rowSegments.isEmpty()) {
        break;
      }
      double rowTop = rowSegments.stream().mapToDouble(LineData::top).min().orElse(previousBottom);
      double maxRowHeight =
          rowSegments.stream().mapToDouble(LineData::maxHeight).max().orElse(0.0d);
      if (rowTop - previousBottom > Math.max(26.0d, maxRowHeight * 2.2d)) {
        break;
      }
      List<Integer> assignments = assignRowToAnchors(rowSegments, centers, pageWidth);
      if (assignments == null) {
        break;
      }
      for (int segmentIndex = 0; segmentIndex < rowSegments.size(); segmentIndex++) {
        columns.get(assignments.get(segmentIndex)).add(rowSegments.get(segmentIndex));
      }
      previousBottom =
          rowSegments.stream().mapToDouble(LineData::bottom).max().orElse(previousBottom);
      matchedRowCount++;
      index++;
    }

    if (matchedRowCount < 2) {
      return null;
    }

    List<BlockData> blocks = new ArrayList<>();
    for (List<LineData> column : columns) {
      blocks.add(asBlock(column));
    }
    return new ColumnCardSpan(index, blocks);
  }

  private static boolean isColumnCardHeader(List<LineData> segments) {
    return segments.size() >= 3
        && segments.size() <= 4
        && segments.stream()
            .allMatch(line -> isShortBoldLabel(line) || isCompactUppercaseLabel(line));
  }

  private static List<Integer> assignRowToAnchors(
      List<LineData> rowSegments, List<Double> centers, double pageWidth) {
    if (rowSegments.isEmpty() || rowSegments.size() > centers.size()) {
      return null;
    }
    List<Double> boundaries = new ArrayList<>();
    for (int index = 0; index < centers.size() - 1; index++) {
      boundaries.add((centers.get(index) + centers.get(index + 1)) / 2.0d);
    }
    List<Integer> assignments = new ArrayList<>();
    int previousColumn = -1;
    for (LineData rowSegment : rowSegments) {
      if (rowSegment.width() > pageWidth * 0.34d) {
        return null;
      }
      int columnIndex = 0;
      while (columnIndex < boundaries.size()
          && rowSegment.centerX() > boundaries.get(columnIndex)) {
        columnIndex++;
      }
      if (columnIndex <= previousColumn) {
        return null;
      }
      assignments.add(columnIndex);
      previousColumn = columnIndex;
    }
    return assignments;
  }

  private static BlockData asBlock(List<LineData> lines) {
    double left = lines.stream().mapToDouble(LineData::left).min().orElse(0.0d);
    double right = lines.stream().mapToDouble(LineData::right).max().orElse(0.0d);
    double top = lines.stream().mapToDouble(LineData::top).min().orElse(0.0d);
    double bottom = lines.stream().mapToDouble(LineData::bottom).max().orElse(0.0d);
    return new BlockData(lines, left, right, top, bottom);
  }

  private static List<LineData> reorderRightInsets(
      List<LineData> lines, double pageWidth, double bodyFont) {
    List<LineData> reordered = new ArrayList<>();
    int index = 0;
    while (index < lines.size()) {
      LineData current = lines.get(index);
      if (index + 1 < lines.size()
          && isLeftFlowLine(current, pageWidth, bodyFont)
          && isRightInsetLine(lines.get(index + 1), pageWidth, bodyFont)
          && lines.get(index + 1).top() <= current.bottom() + Math.max(12.0d, bodyFont)) {
        int insetStart = index + 1;
        int insetEnd = consumeRightInset(lines, insetStart, pageWidth, bodyFont);
        int leftContinuationEnd =
            consumeLeftContinuation(lines, insetEnd, current.left(), pageWidth, bodyFont);
        if (leftContinuationEnd > insetEnd) {
          reordered.add(current);
          for (int continuationIndex = insetEnd;
              continuationIndex < leftContinuationEnd;
              continuationIndex++) {
            reordered.add(lines.get(continuationIndex));
          }
          for (int insetIndex = insetStart; insetIndex < insetEnd; insetIndex++) {
            reordered.add(lines.get(insetIndex));
          }
          index = leftContinuationEnd;
          continue;
        }
      }
      reordered.add(current);
      index++;
    }
    return reordered;
  }

  private static List<LineData> reorderFeatureCardClusters(
      List<LineData> lines, double pageWidth, double bodyFont) {
    if (lines.size() < 6) {
      return lines;
    }
    List<FeatureCardCluster> clusters = detectFeatureCardClusters(lines, pageWidth, bodyFont);
    if (clusters.isEmpty()) {
      return lines;
    }

    Map<LineData, FeatureCardCluster> membership = new HashMap<>();
    for (FeatureCardCluster cluster : clusters) {
      for (LineData line : cluster.members()) {
        membership.put(line, cluster);
      }
    }

    List<LineData> reordered = new ArrayList<>();
    List<FeatureCardCluster> emitted = new ArrayList<>();
    for (LineData line : lines) {
      FeatureCardCluster cluster = membership.get(line);
      if (cluster == null) {
        reordered.add(line);
        continue;
      }
      if (emitted.contains(cluster)) {
        continue;
      }
      reordered.addAll(cluster.orderedLines());
      emitted.add(cluster);
    }
    return reordered;
  }

  private static List<LineData> reorderOutOfOrderTrailingBlocks(
      List<LineData> lines, double bodyFont) {
    List<LineData> reordered = new ArrayList<>();
    int index = 0;
    while (index < lines.size()) {
      LineData current = lines.get(index);
      if (index + 1 < lines.size()
          && isHeading(current, bodyFont)
          && !isCompactUppercaseLabel(current)
          && isCompactUppercaseLabel(lines.get(index + 1))
          && lines.get(index + 1).top() + Math.max(18.0d, bodyFont * 1.2d) < current.top()) {
        int blockStart = index + 1;
        int blockEnd = blockStart + 1;
        while (blockEnd < lines.size() && lines.get(blockEnd).top() < current.top()) {
          blockEnd++;
        }
        reordered.addAll(lines.subList(blockStart, blockEnd));
        reordered.add(current);
        index = blockEnd;
        continue;
      }
      reordered.add(current);
      index++;
    }
    return reordered;
  }

  private static List<FeatureCardCluster> detectFeatureCardClusters(
      List<LineData> lines, double pageWidth, double bodyFont) {
    List<FeatureCardCluster> clusters = new ArrayList<>();
    List<List<LineData>> rows = groupVisualRows(lines);
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      List<LineData> headerRow = rows.get(rowIndex);
      if (!isFeatureCardHeaderRow(headerRow, pageWidth)) {
        continue;
      }

      List<Double> centers = headerRow.stream().map(LineData::centerX).toList();
      List<List<LineData>> columns = new ArrayList<>();
      for (LineData header : headerRow) {
        columns.add(new ArrayList<>(List.of(header)));
      }

      List<LineData> members = new ArrayList<>(headerRow);
      double previousTop = headerRow.stream().mapToDouble(LineData::top).max().orElse(0.0d);
      int matchedRows = 0;
      int nextRowIndex = rowIndex + 1;
      while (nextRowIndex < rows.size()) {
        List<LineData> row = rows.get(nextRowIndex);
        double rowTop = row.stream().mapToDouble(LineData::top).min().orElse(previousTop);
        if (rowTop - previousTop > Math.max(24.0d, bodyFont * 1.9d)) {
          break;
        }
        if (row.stream().anyMatch(line -> line.width() > pageWidth * 0.38d)) {
          break;
        }
        List<Integer> assignments = assignLinesToFeatureColumns(row, centers);
        if (assignments == null) {
          break;
        }
        for (int lineIndex = 0; lineIndex < row.size(); lineIndex++) {
          columns.get(assignments.get(lineIndex)).add(row.get(lineIndex));
        }
        members.addAll(row);
        previousTop = rowTop;
        matchedRows++;
        nextRowIndex++;
      }

      if (matchedRows < 2 || columns.stream().anyMatch(column -> column.size() < 2)) {
        continue;
      }

      List<LineData> orderedLines = new ArrayList<>();
      for (List<LineData> column : columns) {
        orderedLines.addAll(column.stream().sorted(LINE_ORDER).toList());
      }
      clusters.add(new FeatureCardCluster(List.copyOf(members), List.copyOf(orderedLines)));
    }
    return clusters;
  }

  private static List<List<LineData>> groupVisualRows(List<LineData> lines) {
    List<LineData> ordered = lines.stream().sorted(LINE_ORDER).toList();
    List<List<LineData>> rows = new ArrayList<>();
    List<LineData> current = new ArrayList<>();
    double currentTop = Double.NaN;
    for (LineData line : ordered) {
      if (current.isEmpty()) {
        current.add(line);
        currentTop = line.top();
        continue;
      }
      double tolerance = Math.max(8.0d, line.maxHeight() * 0.6d);
      if (Math.abs(line.top() - currentTop) <= tolerance) {
        current.add(line);
      } else {
        rows.add(current.stream().sorted(Comparator.comparingDouble(LineData::left)).toList());
        current = new ArrayList<>();
        current.add(line);
        currentTop = line.top();
      }
    }
    if (!current.isEmpty()) {
      rows.add(current.stream().sorted(Comparator.comparingDouble(LineData::left)).toList());
    }
    return rows;
  }

  private static boolean isFeatureCardHeaderRow(List<LineData> row, double pageWidth) {
    if (row.size() < 3 || row.size() > 4) {
      return false;
    }
    double span = row.get(row.size() - 1).right() - row.get(0).left();
    return span >= pageWidth * 0.55d
        && row.stream().allMatch(PdfExtractionService::isCompactUppercaseLabel);
  }

  private static List<Integer> assignLinesToFeatureColumns(
      List<LineData> row, List<Double> centers) {
    if (row.isEmpty() || row.size() > centers.size()) {
      return null;
    }
    List<Double> boundaries = new ArrayList<>();
    for (int index = 0; index < centers.size() - 1; index++) {
      boundaries.add((centers.get(index) + centers.get(index + 1)) / 2.0d);
    }
    List<Integer> assignments = new ArrayList<>();
    int previousColumn = -1;
    for (LineData line : row) {
      int columnIndex = 0;
      while (columnIndex < boundaries.size() && line.centerX() > boundaries.get(columnIndex)) {
        columnIndex++;
      }
      if (columnIndex <= previousColumn) {
        return null;
      }
      assignments.add(columnIndex);
      previousColumn = columnIndex;
    }
    return assignments;
  }

  private static boolean isLeftFlowLine(LineData line, double pageWidth, double bodyFont) {
    return line.left() < pageWidth * 0.18d
        && line.width() < pageWidth * 0.62d
        && !isHeading(line, bodyFont);
  }

  private static boolean isRightInsetLine(LineData line, double pageWidth, double bodyFont) {
    return line.left() > pageWidth * 0.45d
        && line.width() < pageWidth * 0.42d
        && (isHeading(line, bodyFont)
            || line.boldFraction() >= 0.2d
            || line.width() < pageWidth * 0.3d);
  }

  private static int consumeRightInset(
      List<LineData> lines, int startIndex, double pageWidth, double bodyFont) {
    int index = startIndex + 1;
    double previousBottom = lines.get(startIndex).bottom();
    while (index < lines.size()) {
      LineData candidate = lines.get(index);
      if (candidate.left() <= pageWidth * 0.4d || candidate.width() >= pageWidth * 0.45d) {
        break;
      }
      if (candidate.top() - previousBottom > Math.max(18.0d, bodyFont * 1.8d)) {
        break;
      }
      previousBottom = candidate.bottom();
      index++;
    }
    return index;
  }

  private static int consumeLeftContinuation(
      List<LineData> lines, int startIndex, double anchorLeft, double pageWidth, double bodyFont) {
    int index = startIndex;
    double previousBottom = lines.get(startIndex - 1).bottom();
    while (index < lines.size()) {
      LineData candidate = lines.get(index);
      if (candidate.left() > pageWidth * 0.2d) {
        break;
      }
      if (Math.abs(candidate.left() - anchorLeft) > Math.max(16.0d, bodyFont)) {
        break;
      }
      if (candidate.top() - previousBottom > Math.max(18.0d, bodyFont * 1.8d)) {
        break;
      }
      if (isRightInsetLine(candidate, pageWidth, bodyFont)) {
        break;
      }
      previousBottom = candidate.bottom();
      index++;
    }
    return index;
  }

  private static boolean similarAnchors(List<LineData> reference, List<LineData> candidate) {
    if (reference.size() != candidate.size()) {
      return false;
    }
    for (int index = 0; index < reference.size(); index++) {
      if (Math.abs(reference.get(index).left() - candidate.get(index).left()) > 24.0d) {
        return false;
      }
    }
    return true;
  }

  private static LineData combineSegments(
      List<LineData> segments, Map<TextFragment, String> linksByFragment) {
    List<TextFragment> fragments = new ArrayList<>();
    for (LineData segment : segments) {
      fragments.addAll(segment.fragments());
    }
    return asLine(fragments, linksByFragment);
  }

  private static List<BlockData> groupIntoBlocks(List<LineData> lines, double pageWidth) {
    List<LayoutBlock> blocks = new ArrayList<>();
    for (LineData line : lines.stream().sorted(LINE_ORDER).toList()) {
      LayoutBlock bestBlock = null;
      double bestScore = Double.POSITIVE_INFINITY;
      for (LayoutBlock block : blocks) {
        double verticalGap = line.top() - block.bottom();
        if (verticalGap < -line.maxHeight() * 0.45d
            || verticalGap > Math.max(44.0d, line.maxHeight() * 2.7d)) {
          continue;
        }
        if (!isBlockCompatible(block, line, pageWidth)) {
          continue;
        }
        double score = Math.abs(verticalGap) + Math.abs(line.left() - block.left()) * 0.12d;
        if (score < bestScore) {
          bestScore = score;
          bestBlock = block;
        }
      }
      if (bestBlock == null) {
        blocks.add(new LayoutBlock(line));
      } else {
        bestBlock.add(line);
      }
    }
    return blocks.stream().map(LayoutBlock::snapshot).toList();
  }

  private static boolean isBlockCompatible(LayoutBlock block, LineData line, double pageWidth) {
    double horizontalTolerance = Math.max(18.0d, pageWidth * 0.025d);
    boolean overlaps =
        line.left() <= block.right() + horizontalTolerance
            && line.right() >= block.left() - horizontalTolerance;
    boolean alignedLeft = Math.abs(line.left() - block.left()) <= horizontalTolerance;
    boolean alignedCenter =
        Math.abs(((line.left() + line.right()) / 2.0d) - block.centerX()) <= pageWidth * 0.08d;
    return overlaps || alignedLeft || alignedCenter;
  }

  private static List<BlockData> orderBlocksByLayout(
      List<BlockData> blocks, PDRectangle pageBox, int depth) {
    if (blocks.size() <= 1 || depth >= 8) {
      return blocks.stream()
          .sorted(Comparator.comparingDouble(BlockData::top).thenComparingDouble(BlockData::left))
          .toList();
    }

    BlockSplitCandidate vertical = findVerticalBlockSplit(blocks, pageBox.getWidth());
    BlockSplitCandidate horizontal = findHorizontalBlockSplit(blocks, pageBox.getHeight());

    if (vertical.acceptable()
        && (!horizontal.acceptable() || vertical.gap() > horizontal.gap() * 1.12d)) {
      List<BlockData> ordered = new ArrayList<>();
      ordered.addAll(orderBlocksByLayout(vertical.first(), pageBox, depth + 1));
      ordered.addAll(orderBlocksByLayout(vertical.second(), pageBox, depth + 1));
      return ordered;
    }
    if (horizontal.acceptable()) {
      List<BlockData> ordered = new ArrayList<>();
      ordered.addAll(orderBlocksByLayout(horizontal.first(), pageBox, depth + 1));
      ordered.addAll(orderBlocksByLayout(horizontal.second(), pageBox, depth + 1));
      return ordered;
    }
    return blocks.stream()
        .sorted(Comparator.comparingDouble(BlockData::top).thenComparingDouble(BlockData::left))
        .toList();
  }

  private static BlockSplitCandidate findVerticalBlockSplit(
      List<BlockData> blocks, double pageWidth) {
    List<BlockData> ordered =
        blocks.stream().sorted(Comparator.comparingDouble(BlockData::left)).toList();
    double[] prefixRight = new double[ordered.size()];
    double[] suffixLeft = new double[ordered.size()];

    double runningRight = Double.NEGATIVE_INFINITY;
    for (int index = 0; index < ordered.size(); index++) {
      runningRight = Math.max(runningRight, ordered.get(index).right());
      prefixRight[index] = runningRight;
    }

    double runningLeft = Double.POSITIVE_INFINITY;
    for (int index = ordered.size() - 1; index >= 0; index--) {
      runningLeft = Math.min(runningLeft, ordered.get(index).left());
      suffixLeft[index] = runningLeft;
    }

    double bestGap = 0.0d;
    int bestIndex = -1;
    for (int index = 0; index < ordered.size() - 1; index++) {
      double gap = suffixLeft[index + 1] - prefixRight[index];
      if (gap > bestGap) {
        bestGap = gap;
        bestIndex = index;
      }
    }

    if (bestIndex < 0 || bestGap < Math.max(28.0d, pageWidth * 0.07d)) {
      return BlockSplitCandidate.none();
    }

    List<BlockData> left = ordered.subList(0, bestIndex + 1);
    List<BlockData> right = ordered.subList(bestIndex + 1, ordered.size());
    if (left.isEmpty() || right.isEmpty()) {
      return BlockSplitCandidate.none();
    }
    return new BlockSplitCandidate(bestGap, List.copyOf(left), List.copyOf(right));
  }

  private static BlockSplitCandidate findHorizontalBlockSplit(
      List<BlockData> blocks, double pageHeight) {
    List<BlockData> ordered =
        blocks.stream().sorted(Comparator.comparingDouble(BlockData::top)).toList();
    double[] prefixBottom = new double[ordered.size()];
    double[] suffixTop = new double[ordered.size()];

    double runningBottom = Double.NEGATIVE_INFINITY;
    for (int index = 0; index < ordered.size(); index++) {
      runningBottom = Math.max(runningBottom, ordered.get(index).bottom());
      prefixBottom[index] = runningBottom;
    }

    double runningTop = Double.POSITIVE_INFINITY;
    for (int index = ordered.size() - 1; index >= 0; index--) {
      runningTop = Math.min(runningTop, ordered.get(index).top());
      suffixTop[index] = runningTop;
    }

    double bestGap = 0.0d;
    int bestIndex = -1;
    for (int index = 0; index < ordered.size() - 1; index++) {
      double gap = suffixTop[index + 1] - prefixBottom[index];
      if (gap > bestGap) {
        bestGap = gap;
        bestIndex = index;
      }
    }

    if (bestIndex < 0 || bestGap < Math.max(18.0d, pageHeight * 0.02d)) {
      return BlockSplitCandidate.none();
    }

    List<BlockData> top = ordered.subList(0, bestIndex + 1);
    List<BlockData> bottom = ordered.subList(bestIndex + 1, ordered.size());
    if (top.isEmpty() || bottom.isEmpty()) {
      return BlockSplitCandidate.none();
    }
    return new BlockSplitCandidate(bestGap, List.copyOf(top), List.copyOf(bottom));
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
    double fontSum = 0.0d;
    double maxHeight = 0.0d;
    double top = ordered.get(0).top();
    double bottom = ordered.get(0).bottom();
    double left = ordered.get(0).left();
    double right = ordered.get(0).right();
    int boldFragments = 0;

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
      fontSum += fragment.fontSize();
      maxHeight = Math.max(maxHeight, fragment.height());
      top = Math.min(top, fragment.top());
      bottom = Math.max(bottom, fragment.bottom());
      left = Math.min(left, fragment.left());
      right = Math.max(right, fragment.right());
      if (fragment.bold()) {
        boldFragments++;
      }
    }

    if (currentLink != null) {
      markdownTokens.add("[%s](%s)".formatted(linkedText, currentLink));
    }

    String plainText = normalizeInlineSpacing(String.join(" ", plainTokens));
    String markdownText = normalizeInlineSpacing(String.join(" ", markdownTokens));

    return new LineData(
        ordered,
        plainText,
        markdownText,
        left,
        right,
        top,
        bottom,
        maxFont,
        fontSum / ordered.size(),
        maxHeight,
        (double) boldFragments / ordered.size());
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

  record RenderedDocument(String content, int pageCount, int ocrPages, boolean ocrAvailable) {}

  private record TableSpan(int endExclusive, List<List<String>> rows) {}

  private record LineData(
      List<TextFragment> fragments,
      String plainText,
      String markdownText,
      double left,
      double right,
      double top,
      double bottom,
      double maxFontSize,
      double averageFontSize,
      double maxHeight,
      double boldFraction) {

    private double width() {
      return right - left;
    }

    private double centerX() {
      return (left + right) / 2.0d;
    }
  }

  private record TextFragment(
      String text,
      double left,
      double top,
      double width,
      double height,
      double fontSize,
      boolean bold) {

    private double right() {
      return left + width;
    }

    private double bottom() {
      return top + height;
    }
  }

  private record SplitCandidate(double gap, List<LineData> first, List<LineData> second) {

    private static SplitCandidate none() {
      return new SplitCandidate(0.0d, List.of(), List.of());
    }

    private boolean acceptable() {
      return !first.isEmpty() && !second.isEmpty();
    }
  }

  private record HeadingSpan(String text, LineData firstLine, int nextIndex) {}

  private record BlockData(
      List<LineData> lines, double left, double right, double top, double bottom) {}

  private record TablePreservationSpan(int endExclusive, List<LineData> rows) {}

  private record ColumnCardSpan(int endExclusive, List<BlockData> blocks) {}

  private record FeatureCardCluster(List<LineData> members, List<LineData> orderedLines) {}

  private record BlockSplitCandidate(double gap, List<BlockData> first, List<BlockData> second) {

    private static BlockSplitCandidate none() {
      return new BlockSplitCandidate(0.0d, List.of(), List.of());
    }

    private boolean acceptable() {
      return !first.isEmpty() && !second.isEmpty();
    }
  }

  private static final class FragmentCollectingStripper extends PDFTextStripper {

    private final List<TextFragment> fragments = new ArrayList<>();
    private final GlyphList unicodeGlyphs;
    private int unmappedGlyphs;

    private FragmentCollectingStripper() throws IOException {
      // Match PDFBox's extended glyph list before declaring a mapping unrecoverable.
      try (InputStream additional =
          GlyphList.class.getResourceAsStream(
              "/org/apache/pdfbox/resources/glyphlist/additional.txt")) {
        if (additional == null) {
          throw new IOException("PDFBox's additional Unicode glyph list is missing from the JAR");
        }
        unicodeGlyphs = new GlyphList(GlyphList.getAdobeGlyphList(), additional);
      }
      setSortByPosition(true);
      setAddMoreFormatting(true);
    }

    private List<TextFragment> extractPage(PDDocument document, int pageNumber) throws IOException {
      fragments.clear();
      unmappedGlyphs = 0;
      setStartPage(pageNumber);
      setEndPage(pageNumber);
      getText(document);
      return List.copyOf(fragments);
    }

    @Override
    protected void showGlyph(Matrix textRenderingMatrix, PDFont font, int code, Vector displacement)
        throws IOException {
      if (font.toUnicode(code, unicodeGlyphs) == null) {
        unmappedGlyphs++;
      }
      super.showGlyph(textRenderingMatrix, font, code, displacement);
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
        // Type 3 fonts may omit /Name; unknown style defaults to regular text.
        if (fontName != null && (fontName.contains("Bold") || fontName.contains("Semibold"))) {
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

  private static double dominantBodyFont(List<LineData> lines) {
    Map<Integer, Integer> buckets = new HashMap<>();
    for (LineData line : lines) {
      for (TextFragment fragment : line.fragments()) {
        int bucket = (int) Math.round(fragment.fontSize() * 2);
        buckets.merge(bucket, Math.max(1, fragment.text().length()), Integer::sum);
      }
    }
    return buckets.entrySet().stream()
        .max(Map.Entry.comparingByValue())
        .map(entry -> entry.getKey() / 2.0d)
        .orElse(12.0d);
  }

  private static HeadingSpan consumeHeading(List<LineData> lines, int startIndex, double bodyFont) {
    StringBuilder text = new StringBuilder(lines.get(startIndex).markdownText());
    LineData first = lines.get(startIndex);
    int index = startIndex + 1;
    while (index < lines.size()) {
      LineData candidate = lines.get(index);
      LineData previous = lines.get(index - 1);
      if (!isHeading(candidate, bodyFont)) {
        break;
      }
      if (Math.abs(candidate.maxFontSize() - first.maxFontSize()) > bodyFont * 0.28d) {
        break;
      }
      if (candidate.top() - previous.bottom() > Math.max(bodyFont * 1.1d, previous.maxHeight())) {
        break;
      }
      if (text.length() > 0) {
        text.append(' ');
      }
      text.append(candidate.markdownText());
      index++;
    }
    return new HeadingSpan(normalizeInlineSpacing(text.toString()), first, index);
  }

  private static void appendParagraphSeparator(StringBuilder paragraph, LineData nextLine) {
    if (paragraph.length() == 0) {
      return;
    }
    if (paragraph.charAt(paragraph.length() - 1) == '-') {
      return;
    }
    if (Character.isLetterOrDigit(paragraph.charAt(paragraph.length() - 1))
        && Character.isLetterOrDigit(nextLine.markdownText().charAt(0))) {
      paragraph.append(' ');
    } else {
      paragraph.append(' ');
    }
  }

  private static boolean endsWithStrongSentenceBoundary(String text) {
    return text.endsWith(".")
        || text.endsWith("!")
        || text.endsWith("?")
        || text.endsWith("”")
        || text.endsWith("\"");
  }

  private static boolean isPageNumberNoise(LineData line, PDRectangle pageBox) {
    return DIGITS_ONLY.matcher(line.plainText()).matches()
        && line.bottom() >= pageBox.getHeight() * 0.94d
        && line.width() <= pageBox.getWidth() * 0.08d;
  }

  private static boolean isDecorativeSymbolLine(LineData line) {
    String normalized = line.plainText().replaceAll("\\s+", "");
    if (normalized.isBlank()) {
      return true;
    }
    boolean hasLetterOrDigit = normalized.chars().anyMatch(Character::isLetterOrDigit);
    boolean hasSymbol = normalized.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
    return !hasLetterOrDigit && hasSymbol;
  }

  private static boolean hasMostlyUppercaseLetters(String text) {
    int letterCount = 0;
    int uppercaseCount = 0;
    for (int index = 0; index < text.length(); index++) {
      char current = text.charAt(index);
      if (!Character.isLetter(current)) {
        continue;
      }
      letterCount++;
      if (Character.isUpperCase(current)) {
        uppercaseCount++;
      }
    }
    return letterCount >= 4 && uppercaseCount * 10 >= letterCount * 8;
  }

  private static final class LayoutBlock {

    private final List<LineData> lines = new ArrayList<>();
    private double left;
    private double right;
    private double top;
    private double bottom;

    private LayoutBlock(LineData line) {
      this.left = line.left();
      this.right = line.right();
      this.top = line.top();
      this.bottom = line.bottom();
      lines.add(line);
    }

    private void add(LineData line) {
      lines.add(line);
      left = Math.min(left, line.left());
      right = Math.max(right, line.right());
      top = Math.min(top, line.top());
      bottom = Math.max(bottom, line.bottom());
    }

    private double left() {
      return left;
    }

    private double right() {
      return right;
    }

    private double bottom() {
      return bottom;
    }

    private double centerX() {
      return (left + right) / 2.0d;
    }

    private BlockData snapshot() {
      return new BlockData(lines.stream().sorted(LINE_ORDER).toList(), left, right, top, bottom);
    }
  }
}
