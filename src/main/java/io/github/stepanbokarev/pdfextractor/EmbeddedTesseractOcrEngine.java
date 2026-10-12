package io.github.stepanbokarev.pdfextractor;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.util.LoadLibs;

/** Embedded OCR backend using Tess4J and bundled traineddata files. */
final class EmbeddedTesseractOcrEngine implements OcrEngine {

  private static final Set<String> BUNDLED_LANGUAGES = Set.of("eng", "rus");
  private static final Object STDERR_LOCK = new Object();

  private final Optional<Path> dataRoot;

  EmbeddedTesseractOcrEngine() {
    this.dataRoot = prepareDataRoot();
  }

  @Override
  public String name() {
    return "embedded";
  }

  @Override
  public boolean isAvailable() {
    return dataRoot.isPresent();
  }

  @Override
  public boolean supportsLanguage(String language) {
    if (language == null || language.isBlank()) {
      return true;
    }
    Set<String> requestedLanguages = parseLanguages(language);
    return BUNDLED_LANGUAGES.containsAll(requestedLanguages);
  }

  @Override
  public String ocr(BufferedImage image, String language) throws IOException {
    if (dataRoot.isEmpty()) {
      throw new IOException("Bundled OCR data is not available for Tess4J");
    }
    if (!supportsLanguage(language)) {
      throw new IOException("Bundled OCR languages do not cover '" + language + "'");
    }

    Tesseract tesseract = new Tesseract();
    tesseract.setDatapath(dataRoot.get().toString());
    tesseract.setLanguage(language == null || language.isBlank() ? "eng" : language);
    tesseract.setPageSegMode(3);
    tesseract.setVariable("preserve_interword_spaces", "1");
    synchronized (STDERR_LOCK) {
      PrintStream originalErr = System.err;
      ByteArrayOutputStream interceptedErr = new ByteArrayOutputStream();
      try (PrintStream quietErr = new PrintStream(interceptedErr, true, StandardCharsets.UTF_8)) {
        System.setErr(quietErr);
        return tesseract.doOCR(image);
      } catch (UnsatisfiedLinkError exception) {
        throw new IOException(
            "Native Tesseract library is unavailable. Install Tesseract on this system to enable"
                + " OCR.",
            exception);
      } catch (TesseractException exception) {
        String nativeError = interceptedErr.toString(StandardCharsets.UTF_8).trim();
        String detail = exception.getMessage();
        if (!nativeError.isBlank()) {
          detail = detail + " - " + nativeError;
        }
        throw new IOException("Embedded OCR failed - " + detail, exception);
      } finally {
        System.setErr(originalErr);
      }
    }
  }

  private static Optional<Path> prepareDataRoot() {
    try {
      Path tessdataDir = LoadLibs.extractTessResources("tessdata").toPath();
      for (String language : BUNDLED_LANGUAGES) {
        Path target = tessdataDir.resolve(language + ".traineddata");
        copyResource("/tessdata/" + language + ".traineddata.b64", target);
      }
      return Optional.of(tessdataDir);
    } catch (IOException exception) {
      return Optional.empty();
    }
  }

  private static void copyResource(String resourcePath, Path target) throws IOException {
    try (InputStream inputStream =
        EmbeddedTesseractOcrEngine.class.getResourceAsStream(resourcePath)) {
      if (inputStream == null) {
        throw new IOException("Missing bundled OCR resource: " + resourcePath);
      }
      byte[] decoded = Base64.getMimeDecoder().decode(inputStream.readAllBytes());
      Files.write(target, decoded);
    }
  }

  private static Set<String> parseLanguages(String languageExpression) {
    return Arrays.stream(languageExpression.split("\\+"))
        .map(String::trim)
        .filter(token -> !token.isBlank())
        .collect(LinkedHashSet::new, Set::add, Set::addAll);
  }
}
