package io.github.stepanbokarev.pdfextractor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;

/** Chooses the best available OCR backend for the current environment. */
final class PdfOcrEngine {

  private final List<OcrEngine> engines;

  PdfOcrEngine() {
    this(List.of(new EmbeddedTesseractOcrEngine(), new TesseractCliOcrEngine()));
  }

  PdfOcrEngine(List<OcrEngine> engines) {
    this.engines = List.copyOf(engines);
  }

  boolean isAvailable() {
    return engines.stream().anyMatch(OcrEngine::isAvailable);
  }

  boolean canOcr(String language) {
    return engines.stream()
        .anyMatch(engine -> engine.isAvailable() && engine.supportsLanguage(language));
  }

  String ocr(BufferedImage image, String language) throws IOException, InterruptedException {
    IOException lastFailure = null;
    for (OcrEngine engine : engines) {
      if (!engine.isAvailable() || !engine.supportsLanguage(language)) {
        continue;
      }
      try {
        return engine.ocr(image, language);
      } catch (IOException exception) {
        lastFailure = exception;
      }
    }
    if (lastFailure != null) {
      throw lastFailure;
    }
    throw new IOException("No OCR backend is available for language expression '" + language + "'");
  }
}
