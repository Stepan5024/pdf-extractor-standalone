package io.github.stepanbokarev.pdfextractor;

import java.awt.image.BufferedImage;
import java.io.IOException;

/** OCR backend abstraction for embedded or external engines. */
interface OcrEngine {

  String name();

  boolean isAvailable();

  boolean supportsLanguage(String language);

  String ocr(BufferedImage image, String language) throws IOException, InterruptedException;
}
