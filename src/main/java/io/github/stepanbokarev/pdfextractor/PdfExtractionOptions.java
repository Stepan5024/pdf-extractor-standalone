package io.github.stepanbokarev.pdfextractor;

import java.nio.file.Path;

/** Immutable extraction options for a PDF conversion run. */
public record PdfExtractionOptions(
    Path inputFile,
    Path outputFile,
    PdfFileFormat fileFormat,
    OcrMode ocrMode,
    String ocrLanguage,
    int ocrDpi) {}
