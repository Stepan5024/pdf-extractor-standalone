package io.github.stepanbokarev.pdfextractor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Combines extracted PDF markdown files into a single bundle and restores them back. */
final class MarkdownBundleService {

  static final String BLOCK_START = "<<<FILE>>>";
  static final String PATH_PREFIX = "PATH: ";
  static final String SOURCE_PREFIX = "SOURCE: ";
  static final String MODIFIED_PREFIX = "LAST_MODIFIED_EPOCH: ";
  static final String DELIMITER_PREFIX = "END_DELIMITER: ";

  private final PdfExtractionService extractionService = new PdfExtractionService();

  BundleCombineOutcome combineDirectory(
      Path inputDir, Path outputFile, OcrMode ocrMode, String ocrLanguage, int ocrDpi)
      throws IOException, InterruptedException {
    List<Path> pdfFiles = listPdfFiles(inputDir);
    if (pdfFiles.isEmpty()) {
      throw new IOException("No PDF files found in directory: " + inputDir);
    }

    List<BundleEntry> entries = new ArrayList<>();
    int totalPages = 0;
    int totalOcrPages = 0;
    boolean ocrAvailable = false;
    int counter = 0;

    for (Path pdfFile : pdfFiles) {
      Path relativePdfPath = inputDir.relativize(pdfFile);
      String relativeMarkdownPath = replaceExtension(normalizePath(relativePdfPath), ".md");
      PdfExtractionOptions options =
          new PdfExtractionOptions(
              pdfFile, outputFile, PdfFileFormat.md, ocrMode, ocrLanguage, ocrDpi);
      PdfExtractionService.RenderedDocument rendered = extractionService.render(options);
      long lastModifiedEpoch = Files.getLastModifiedTime(pdfFile).toMillis() / 1000L;
      String delimiter =
          "__END_FILE_"
              + Integer.toHexString((relativeMarkdownPath + "|pdf-folder|" + counter).hashCode())
                  .replace('-', '0')
              + "__";
      entries.add(
          new BundleEntry(
              relativeMarkdownPath,
              "pdf-folder",
              lastModifiedEpoch,
              delimiter,
              rendered.content()));
      totalPages += rendered.pageCount();
      totalOcrPages += rendered.ocrPages();
      ocrAvailable = ocrAvailable || rendered.ocrAvailable();
      counter++;
    }

    writeBundle(outputFile, entries);
    return new BundleCombineOutcome(
        outputFile, entries.size(), totalPages, totalOcrPages, ocrAvailable);
  }

  BundleSplitOutcome splitBundle(Path bundleFile, Path outputDir) throws IOException {
    List<BundleEntry> entries =
        parseStructuredBundle(Files.readString(bundleFile, StandardCharsets.UTF_8));
    if (entries.isEmpty()) {
      throw new IOException("No bundle entries found in file: " + bundleFile);
    }

    Files.createDirectories(outputDir);
    int restoredFiles = 0;
    for (BundleEntry entry : entries) {
      Path relativePath = Path.of(entry.relativePath());
      Path targetFile = outputDir.resolve(relativePath).normalize();
      if (!targetFile.startsWith(outputDir)) {
        throw new IOException(
            "Refusing to write outside output directory: " + entry.relativePath());
      }
      Path parent = targetFile.getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(targetFile, entry.content(), StandardCharsets.UTF_8);
      restoredFiles++;
    }
    return new BundleSplitOutcome(outputDir, restoredFiles);
  }

  private static List<Path> listPdfFiles(Path inputDir) throws IOException {
    List<Path> pdfFiles = new ArrayList<>();
    Files.walkFileTree(
        inputDir,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()
                && file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf")) {
              pdfFiles.add(file);
            }
            return FileVisitResult.CONTINUE;
          }
        });
    pdfFiles.sort(Comparator.comparing(path -> normalizePath(inputDir.relativize(path))));
    return pdfFiles;
  }

  private static void writeBundle(Path outputFile, List<BundleEntry> entries) throws IOException {
    Path parent = outputFile.toAbsolutePath().getParent();
    if (parent != null) {
      Files.createDirectories(parent);
    }
    StringBuilder content = new StringBuilder();
    for (BundleEntry entry : entries) {
      content.append(BLOCK_START).append('\n');
      content.append(PATH_PREFIX).append(entry.relativePath()).append('\n');
      content.append(SOURCE_PREFIX).append(entry.source()).append('\n');
      content.append(MODIFIED_PREFIX).append(entry.lastModifiedEpoch()).append('\n');
      content.append(DELIMITER_PREFIX).append(entry.delimiter()).append('\n');
      content.append('\n');
      content.append(entry.content());
      if (!entry.content().endsWith("\n")) {
        content.append('\n');
      }
      content.append(entry.delimiter()).append('\n');
    }
    Files.writeString(outputFile, content.toString(), StandardCharsets.UTF_8);
  }

  private static List<BundleEntry> parseStructuredBundle(String rawContent) {
    List<BundleEntry> entries = new ArrayList<>();
    String normalizedContent = stripBom(rawContent);
    int cursor = 0;

    while (true) {
      int blockIndex = normalizedContent.indexOf(BLOCK_START, cursor);
      if (blockIndex < 0) {
        break;
      }

      int pathLineStart = skipLineBreak(normalizedContent, blockIndex + BLOCK_START.length());
      ParsedLine pathLine = readLine(normalizedContent, pathLineStart);
      ParsedLine sourceLine = readLine(normalizedContent, pathLine.nextIndex());
      ParsedLine modifiedLine = readLine(normalizedContent, sourceLine.nextIndex());
      ParsedLine delimiterLine = readLine(normalizedContent, modifiedLine.nextIndex());

      if (!pathLine.value().startsWith(PATH_PREFIX)
          || !sourceLine.value().startsWith(SOURCE_PREFIX)
          || !modifiedLine.value().startsWith(MODIFIED_PREFIX)
          || !delimiterLine.value().startsWith(DELIMITER_PREFIX)) {
        throw new IllegalArgumentException("Corrupted bundle block near position " + blockIndex);
      }

      String relativePath = pathLine.value().substring(PATH_PREFIX.length()).trim();
      String source = sourceLine.value().substring(SOURCE_PREFIX.length()).trim();
      long lastModifiedEpoch =
          Long.parseLong(modifiedLine.value().substring(MODIFIED_PREFIX.length()).trim());
      String delimiter = delimiterLine.value().substring(DELIMITER_PREFIX.length()).trim();
      int contentStart = skipLineBreak(normalizedContent, delimiterLine.nextIndex());
      DelimiterMatch delimiterMatch = findDelimiter(normalizedContent, delimiter, contentStart);
      if (delimiterMatch.index() < 0) {
        if (normalizedContent.startsWith(delimiter, contentStart)) {
          delimiterMatch = new DelimiterMatch(contentStart, delimiter.length());
        } else {
          throw new IllegalArgumentException("Missing bundle delimiter for " + relativePath);
        }
      }

      String content = normalizedContent.substring(contentStart, delimiterMatch.index());
      content = trimTrailingLineBreak(content);
      entries.add(new BundleEntry(relativePath, source, lastModifiedEpoch, delimiter, content));

      cursor = delimiterMatch.index() + delimiterMatch.length();
      cursor = skipLineBreak(normalizedContent, cursor);
    }

    return entries;
  }

  private static String replaceExtension(String path, String newExtension) {
    int lastDot = path.lastIndexOf('.');
    if (lastDot >= 0) {
      return path.substring(0, lastDot) + newExtension;
    }
    return path + newExtension;
  }

  private static String normalizePath(Path path) {
    return path.toString().replace("\\", "/");
  }

  private static String stripBom(String rawContent) {
    if (!rawContent.isEmpty() && rawContent.charAt(0) == '\uFEFF') {
      return rawContent.substring(1);
    }
    return rawContent;
  }

  private static int skipLineBreak(String rawContent, int index) {
    if (index >= rawContent.length()) {
      return index;
    }
    if (rawContent.charAt(index) == '\r') {
      index++;
      if (index < rawContent.length() && rawContent.charAt(index) == '\n') {
        index++;
      }
      return index;
    }
    if (rawContent.charAt(index) == '\n') {
      return index + 1;
    }
    return index;
  }

  private static ParsedLine readLine(String rawContent, int startIndex) {
    int endIndex = rawContent.indexOf('\n', startIndex);
    if (endIndex < 0) {
      return new ParsedLine(
          trimTrailingCarriageReturn(rawContent.substring(startIndex)), rawContent.length());
    }
    return new ParsedLine(
        trimTrailingCarriageReturn(rawContent.substring(startIndex, endIndex)), endIndex + 1);
  }

  private static String trimTrailingCarriageReturn(String value) {
    if (!value.isEmpty() && value.charAt(value.length() - 1) == '\r') {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  private static String trimTrailingLineBreak(String value) {
    if (value.endsWith("\r\n")) {
      return value.substring(0, value.length() - 2);
    }
    if (value.endsWith("\n") || value.endsWith("\r")) {
      return value.substring(0, value.length() - 1);
    }
    return value;
  }

  private static DelimiterMatch findDelimiter(
      String rawContent, String delimiter, int contentStart) {
    String crlfToken = "\r\n" + delimiter;
    String lfToken = "\n" + delimiter;

    int crlfIndex = rawContent.indexOf(crlfToken, contentStart);
    int lfIndex = rawContent.indexOf(lfToken, contentStart);

    if (crlfIndex >= 0 && (lfIndex < 0 || crlfIndex <= lfIndex)) {
      return new DelimiterMatch(crlfIndex, crlfToken.length());
    }
    if (lfIndex >= 0) {
      return new DelimiterMatch(lfIndex, lfToken.length());
    }
    return new DelimiterMatch(-1, 0);
  }

  record BundleCombineOutcome(
      Path outputFile,
      int inputFileCount,
      int totalPages,
      int totalOcrPages,
      boolean ocrAvailable) {}

  record BundleSplitOutcome(Path outputDir, int restoredFileCount) {}

  private record BundleEntry(
      String relativePath,
      String source,
      long lastModifiedEpoch,
      String delimiter,
      String content) {}

  private record ParsedLine(String value, int nextIndex) {}

  private record DelimiterMatch(int index, int length) {}
}
