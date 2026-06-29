package io.github.stepanbokarev.pdfextractor;

import java.nio.file.Path;

/** Extraction result summary. */
public record PdfExtractionOutcome(
    Path outputFile, PdfFileFormat fileFormat, int pageCount, int ocrPages, boolean ocrAvailable) {}
