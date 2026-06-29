package io.github.stepanbokarev.pdfextractor;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone entry point for PDF to Markdown/TXT extraction with local OCR. */
public final class PdfExtractorApplication {

  private static final int EXIT_SUCCESS = 0;
  private static final int EXIT_VALIDATION = 2;
  private static final int EXIT_FAILURE = 3;

  private final PrintStream out;
  private final PrintStream err;

  public PdfExtractorApplication(PrintStream out, PrintStream err) {
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args) {
    int exitCode = new PdfExtractorApplication(System.out, System.err).run(args);
    System.exit(exitCode);
  }

  int run(String[] args) {
    try {
      ParsedArguments parsed = ParsedArguments.parse(args);
      if (parsed.showHelp()) {
        out.print(usage());
        return EXIT_SUCCESS;
      }

      ValidationResult validation = validate(parsed);
      if (!validation.valid()) {
        err.println("Error: " + validation.message());
        err.println();
        err.print(usage());
        return EXIT_VALIDATION;
      }

      PdfExtractionOutcome outcome = new PdfExtractionService().extract(validation.options());
      out.printf(
          "Extracted %d pages to %s (format=%s, ocrPages=%d, ocrAvailable=%s)%n",
          outcome.pageCount(),
          outcome.outputFile(),
          outcome.fileFormat(),
          outcome.ocrPages(),
          outcome.ocrAvailable());
      return EXIT_SUCCESS;
    } catch (IllegalArgumentException exception) {
      err.println("Error: " + exception.getMessage());
      err.println();
      err.print(usage());
      return EXIT_VALIDATION;
    } catch (Exception exception) {
      err.println("Error: " + exception.getMessage());
      return EXIT_FAILURE;
    }
  }

  static String usage() {
    return """
        Usage:
          java -jar pdf-extractor-standalone-1.0.0.jar <input.pdf> [options]

        Options:
          --output-file <path>    Write extracted content to this file
          --file-format <md|txt>  Output format (default: md)
          --ocr-mode <auto|always|off>
                                  OCR policy (default: auto)
          --ocr-language <expr>   OCR languages, e.g. eng+rus (default: eng+rus)
          --ocr-dpi <number>      DPI used for OCR rendering (default: 300)
          --help                  Show this help

        Notes:
          - OCR works offline.
          - Bundled OCR languages: eng, rus.
          - Other OCR languages require an installed tesseract executable.
        """;
  }

  private static ValidationResult validate(ParsedArguments parsed) {
    if (parsed.inputFile() == null) {
      return ValidationResult.invalid("Input PDF path is required");
    }
    Path inputFile = parsed.inputFile().toAbsolutePath();
    if (!Files.isRegularFile(inputFile)) {
      return ValidationResult.invalid("PDF file not found: " + inputFile);
    }
    if (parsed.ocrDpi() < 72) {
      return ValidationResult.invalid("OCR DPI must be at least 72");
    }

    Path outputFile =
        parsed.outputFile() != null
            ? parsed.outputFile().toAbsolutePath()
            : deriveOutputFile(inputFile, parsed.fileFormat().name());
    Path parent = outputFile.getParent();
    if (parent == null) {
      return ValidationResult.invalid("Output file must have a parent directory");
    }

    return ValidationResult.valid(
        new PdfExtractionOptions(
            inputFile,
            outputFile,
            parsed.fileFormat(),
            parsed.ocrMode(),
            parsed.ocrLanguage(),
            parsed.ocrDpi()));
  }

  private static Path deriveOutputFile(Path source, String extension) {
    String fileName = source.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    String baseName = dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    return source.toAbsolutePath().getParent().resolve(baseName + "." + extension);
  }

  private record ValidationResult(boolean valid, String message, PdfExtractionOptions options) {

    private static ValidationResult valid(PdfExtractionOptions options) {
      return new ValidationResult(true, "", options);
    }

    private static ValidationResult invalid(String message) {
      return new ValidationResult(false, message, null);
    }
  }

  private record ParsedArguments(
      Path inputFile,
      Path outputFile,
      PdfFileFormat fileFormat,
      OcrMode ocrMode,
      String ocrLanguage,
      int ocrDpi,
      boolean showHelp) {

    private static ParsedArguments parse(String[] args) {
      Path inputFile = null;
      Path outputFile = null;
      PdfFileFormat fileFormat = PdfFileFormat.md;
      OcrMode ocrMode = OcrMode.auto;
      String ocrLanguage = "eng+rus";
      int ocrDpi = 300;
      boolean showHelp = false;

      for (int index = 0; index < args.length; index++) {
        String arg = args[index];
        switch (arg) {
          case "--help":
          case "-h":
            showHelp = true;
            break;
          case "--output-file":
            outputFile = Path.of(nextValue(args, ++index, arg));
            break;
          case "--file-format":
            fileFormat = PdfFileFormat.valueOf(nextValue(args, ++index, arg));
            break;
          case "--ocr-mode":
            ocrMode = OcrMode.valueOf(nextValue(args, ++index, arg));
            break;
          case "--ocr-language":
            ocrLanguage = nextValue(args, ++index, arg);
            break;
          case "--ocr-dpi":
            ocrDpi = Integer.parseInt(nextValue(args, ++index, arg));
            break;
          default:
            if (arg.startsWith("--")) {
              throw new IllegalArgumentException("Unknown option: " + arg);
            }
            if (inputFile != null) {
              throw new IllegalArgumentException("Only one input PDF file is supported");
            }
            inputFile = Path.of(arg);
            break;
        }
      }

      return new ParsedArguments(
          inputFile, outputFile, fileFormat, ocrMode, ocrLanguage, ocrDpi, showHelp);
    }

    private static String nextValue(String[] args, int index, String optionName) {
      if (index >= args.length) {
        throw new IllegalArgumentException("Missing value for option " + optionName);
      }
      return args[index];
    }
  }
}
