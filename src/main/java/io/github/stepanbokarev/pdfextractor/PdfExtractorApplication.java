package io.github.stepanbokarev.pdfextractor;

import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Scanner;

/** Standalone entry point for PDF extraction, folder combine, and bundle split workflows. */
public final class PdfExtractorApplication {

  private static final int EXIT_SUCCESS = 0;
  private static final int EXIT_VALIDATION = 2;
  private static final int EXIT_FAILURE = 3;

  private final InputStream in;
  private final PrintStream out;
  private final PrintStream err;

  public PdfExtractorApplication(PrintStream out, PrintStream err) {
    this(System.in, out, err);
  }

  PdfExtractorApplication(InputStream in, PrintStream out, PrintStream err) {
    this.in = in;
    this.out = out;
    this.err = err;
  }

  public static void main(String[] args) {
    int exitCode = new PdfExtractorApplication(System.in, System.out, System.err).run(args);
    System.exit(exitCode);
  }

  int run(String[] args) {
    try {
      ParsedCommand parsed =
          args.length == 0 ? promptInteractiveCommand() : ParsedCommand.parse(args);
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

      return execute(validation.command());
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
          java -jar pdf-extractor-standalone-3.0.2.jar <input.pdf> [options]
          java -jar pdf-extractor-standalone-3.0.2.jar extract <input.pdf> [options]
          java -jar pdf-extractor-standalone-3.0.2.jar combine <input-dir> [options]
          java -jar pdf-extractor-standalone-3.0.2.jar split <combined.md> [options]

        Extract options:
          --output-file <path>    Write extracted content to this file
          --file-format <md|txt>  Output format (default: md)
          --ocr-mode <auto|always|off>
                                  OCR policy (default: auto)
          --ocr-language <expr>   OCR languages, e.g. eng+rus (default: eng+rus)
          --ocr-dpi <number>      DPI used for OCR rendering (default: 300)

        Combine options:
          --output-file <path>    Output combined markdown bundle (default: <input-dir>/combined.md)
          --ocr-mode <auto|always|off>
          --ocr-language <expr>
          --ocr-dpi <number>

        Split options:
          --output-dir <path>     Directory to restore markdown files into
                                  (default: <combined-file-name>-split)

        Notes:
          - Running without arguments opens a simple 3-mode menu.
          - Combine mode recursively scans a regular folder, not a git repository.
          - Combined markdown uses the same structured bundle markers as FileCombiner/UnZip.
          - OCR works offline.
          - Bundled OCR languages: eng, rus.
          - Other OCR languages require an installed tesseract executable.
        """;
  }

  private int execute(ParsedCommand command) throws Exception {
    return switch (command.mode()) {
      case EXTRACT -> executeExtract(command);
      case COMBINE -> executeCombine(command);
      case SPLIT -> executeSplit(command);
    };
  }

  private int executeExtract(ParsedCommand command) throws Exception {
    PdfExtractionOutcome outcome = new PdfExtractionService().extract(command.extractOptions());
    out.printf(
        "Extracted %d pages to %s (format=%s, ocrPages=%d, ocrAvailable=%s)%n",
        outcome.pageCount(),
        outcome.outputFile(),
        outcome.fileFormat(),
        outcome.ocrPages(),
        outcome.ocrAvailable());
    return EXIT_SUCCESS;
  }

  private int executeCombine(ParsedCommand command) throws Exception {
    MarkdownBundleService.BundleCombineOutcome outcome =
        new MarkdownBundleService()
            .combineDirectory(
                command.inputDir(),
                command.outputFile(),
                command.ocrMode(),
                command.ocrLanguage(),
                command.ocrDpi());
    out.printf(
        "Combined %d PDF files into %s (pages=%d, ocrPages=%d, ocrAvailable=%s)%n",
        outcome.inputFileCount(),
        outcome.outputFile(),
        outcome.totalPages(),
        outcome.totalOcrPages(),
        outcome.ocrAvailable());
    return EXIT_SUCCESS;
  }

  private int executeSplit(ParsedCommand command) throws Exception {
    MarkdownBundleService.BundleSplitOutcome outcome =
        new MarkdownBundleService().splitBundle(command.bundleFile(), command.outputDir());
    out.printf(
        "Restored %d markdown files into %s%n", outcome.restoredFileCount(), outcome.outputDir());
    return EXIT_SUCCESS;
  }

  private ValidationResult validate(ParsedCommand parsed) {
    return switch (parsed.mode()) {
      case EXTRACT -> validateExtract(parsed);
      case COMBINE -> validateCombine(parsed);
      case SPLIT -> validateSplit(parsed);
    };
  }

  private static ValidationResult validateExtract(ParsedCommand parsed) {
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
            : deriveFileOutput(inputFile, parsed.fileFormat().name());
    Path parent = outputFile.getParent();
    if (parent == null) {
      return ValidationResult.invalid("Output file must have a parent directory");
    }

    return ValidationResult.valid(
        parsed.withResolvedExtractOptions(
            new PdfExtractionOptions(
                inputFile,
                outputFile,
                parsed.fileFormat(),
                parsed.ocrMode(),
                parsed.ocrLanguage(),
                parsed.ocrDpi())));
  }

  private static ValidationResult validateCombine(ParsedCommand parsed) {
    if (parsed.inputDir() == null) {
      return ValidationResult.invalid("Input directory path is required");
    }
    Path inputDir = parsed.inputDir().toAbsolutePath();
    if (!Files.isDirectory(inputDir)) {
      return ValidationResult.invalid("Input directory not found: " + inputDir);
    }
    if (parsed.ocrDpi() < 72) {
      return ValidationResult.invalid("OCR DPI must be at least 72");
    }

    Path outputFile =
        parsed.outputFile() != null
            ? parsed.outputFile().toAbsolutePath()
            : inputDir.resolve("combined.md");
    Path parent = outputFile.getParent();
    if (parent == null) {
      return ValidationResult.invalid("Output file must have a parent directory");
    }
    return ValidationResult.valid(parsed.withResolvedCombineOutput(inputDir, outputFile));
  }

  private static ValidationResult validateSplit(ParsedCommand parsed) {
    if (parsed.bundleFile() == null) {
      return ValidationResult.invalid("Combined markdown file path is required");
    }
    Path bundleFile = parsed.bundleFile().toAbsolutePath();
    if (!Files.isRegularFile(bundleFile)) {
      return ValidationResult.invalid("Combined markdown file not found: " + bundleFile);
    }

    Path outputDir =
        parsed.outputDir() != null
            ? parsed.outputDir().toAbsolutePath()
            : deriveSplitOutputDir(bundleFile);
    return ValidationResult.valid(parsed.withResolvedSplitOutput(bundleFile, outputDir));
  }

  private ParsedCommand promptInteractiveCommand() {
    Scanner scanner = new Scanner(in);
    out.println("Select mode:");
    out.println("1. Extract one PDF file");
    out.println("2. Combine a folder of PDFs into one markdown bundle");
    out.println("3. Split one bundle markdown into markdown files");
    out.print("Choice [1/2/3]: ");
    String choice = scanner.nextLine().trim();

    return switch (choice) {
      case "1", "" -> promptExtract(scanner);
      case "2" -> promptCombine(scanner);
      case "3" -> promptSplit(scanner);
      default -> throw new IllegalArgumentException("Unknown menu choice: " + choice);
    };
  }

  private ParsedCommand promptExtract(Scanner scanner) {
    Path inputFile = Path.of(prompt(scanner, "Input PDF path: "));
    String outputValue = prompt(scanner, "Output file [optional]: ");
    Path outputFile = outputValue.isBlank() ? null : Path.of(outputValue);
    return ParsedCommand.extract(
        inputFile, outputFile, PdfFileFormat.md, OcrMode.auto, "eng+rus", 300, false);
  }

  private ParsedCommand promptCombine(Scanner scanner) {
    Path inputDir = Path.of(prompt(scanner, "Input folder with PDFs: "));
    String outputValue = prompt(scanner, "Combined markdown file [optional]: ");
    Path outputFile = outputValue.isBlank() ? null : Path.of(outputValue);
    return ParsedCommand.combine(inputDir, outputFile, OcrMode.auto, "eng+rus", 300, false);
  }

  private ParsedCommand promptSplit(Scanner scanner) {
    Path bundleFile = Path.of(prompt(scanner, "Combined markdown file: "));
    String outputValue = prompt(scanner, "Output directory [optional]: ");
    Path outputDir = outputValue.isBlank() ? null : Path.of(outputValue);
    return ParsedCommand.split(bundleFile, outputDir, false);
  }

  private static String prompt(Scanner scanner, String message) {
    System.out.print(message);
    return scanner.nextLine().trim();
  }

  private static Path deriveFileOutput(Path source, String extension) {
    String fileName = source.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    String baseName = dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    return source.toAbsolutePath().getParent().resolve(baseName + "." + extension);
  }

  private static Path deriveSplitOutputDir(Path bundleFile) {
    Path parent = bundleFile.toAbsolutePath().getParent();
    if (parent == null) {
      throw new IllegalArgumentException("Combined markdown file must have a parent directory");
    }
    String fileName = bundleFile.getFileName().toString();
    int dotIndex = fileName.lastIndexOf('.');
    String baseName = dotIndex >= 0 ? fileName.substring(0, dotIndex) : fileName;
    return parent.resolve(baseName + "-split");
  }

  private enum CommandMode {
    EXTRACT,
    COMBINE,
    SPLIT
  }

  private record ValidationResult(boolean valid, String message, ParsedCommand command) {

    private static ValidationResult valid(ParsedCommand command) {
      return new ValidationResult(true, "", command);
    }

    private static ValidationResult invalid(String message) {
      return new ValidationResult(false, message, null);
    }
  }

  private record ParsedCommand(
      CommandMode mode,
      Path inputFile,
      Path inputDir,
      Path bundleFile,
      Path outputFile,
      Path outputDir,
      PdfFileFormat fileFormat,
      OcrMode ocrMode,
      String ocrLanguage,
      int ocrDpi,
      boolean showHelp,
      PdfExtractionOptions extractOptions) {

    private static ParsedCommand parse(String[] args) {
      if (args.length == 0) {
        throw new IllegalArgumentException("No arguments provided");
      }
      String first = args[0];
      if ("--help".equals(first) || "-h".equals(first)) {
        return help();
      }
      if ("extract".equalsIgnoreCase(first)) {
        return parseExtract(slice(args, 1));
      }
      if ("combine".equalsIgnoreCase(first)) {
        return parseCombine(slice(args, 1));
      }
      if ("split".equalsIgnoreCase(first)) {
        return parseSplit(slice(args, 1));
      }
      return parseExtract(args);
    }

    private static ParsedCommand parseExtract(String[] args) {
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
          case "--help", "-h" -> showHelp = true;
          case "--output-file" -> outputFile = Path.of(nextValue(args, ++index, arg));
          case "--file-format" -> fileFormat = PdfFileFormat.valueOf(nextValue(args, ++index, arg));
          case "--ocr-mode" -> ocrMode = OcrMode.valueOf(nextValue(args, ++index, arg));
          case "--ocr-language" -> ocrLanguage = nextValue(args, ++index, arg);
          case "--ocr-dpi" -> ocrDpi = Integer.parseInt(nextValue(args, ++index, arg));
          default -> {
            if (arg.startsWith("--")) {
              throw new IllegalArgumentException("Unknown option: " + arg);
            }
            if (inputFile != null) {
              throw new IllegalArgumentException("Only one input PDF file is supported");
            }
            inputFile = Path.of(arg);
          }
        }
      }

      return extract(inputFile, outputFile, fileFormat, ocrMode, ocrLanguage, ocrDpi, showHelp);
    }

    private static ParsedCommand parseCombine(String[] args) {
      Path inputDir = null;
      Path outputFile = null;
      OcrMode ocrMode = OcrMode.auto;
      String ocrLanguage = "eng+rus";
      int ocrDpi = 300;
      boolean showHelp = false;

      for (int index = 0; index < args.length; index++) {
        String arg = args[index];
        switch (arg) {
          case "--help", "-h" -> showHelp = true;
          case "--output-file" -> outputFile = Path.of(nextValue(args, ++index, arg));
          case "--ocr-mode" -> ocrMode = OcrMode.valueOf(nextValue(args, ++index, arg));
          case "--ocr-language" -> ocrLanguage = nextValue(args, ++index, arg);
          case "--ocr-dpi" -> ocrDpi = Integer.parseInt(nextValue(args, ++index, arg));
          default -> {
            if (arg.startsWith("--")) {
              throw new IllegalArgumentException("Unknown option: " + arg);
            }
            if (inputDir != null) {
              throw new IllegalArgumentException("Only one input directory is supported");
            }
            inputDir = Path.of(arg);
          }
        }
      }

      return combine(inputDir, outputFile, ocrMode, ocrLanguage, ocrDpi, showHelp);
    }

    private static ParsedCommand parseSplit(String[] args) {
      Path bundleFile = null;
      Path outputDir = null;
      boolean showHelp = false;

      for (int index = 0; index < args.length; index++) {
        String arg = args[index];
        switch (arg) {
          case "--help", "-h" -> showHelp = true;
          case "--output-dir" -> outputDir = Path.of(nextValue(args, ++index, arg));
          default -> {
            if (arg.startsWith("--")) {
              throw new IllegalArgumentException("Unknown option: " + arg);
            }
            if (bundleFile != null) {
              throw new IllegalArgumentException("Only one combined markdown file is supported");
            }
            bundleFile = Path.of(arg);
          }
        }
      }

      return split(bundleFile, outputDir, showHelp);
    }

    private static ParsedCommand help() {
      return new ParsedCommand(
          CommandMode.EXTRACT,
          null,
          null,
          null,
          null,
          null,
          PdfFileFormat.md,
          OcrMode.auto,
          "eng+rus",
          300,
          true,
          null);
    }

    private static ParsedCommand extract(
        Path inputFile,
        Path outputFile,
        PdfFileFormat fileFormat,
        OcrMode ocrMode,
        String ocrLanguage,
        int ocrDpi,
        boolean showHelp) {
      return new ParsedCommand(
          CommandMode.EXTRACT,
          inputFile,
          null,
          null,
          outputFile,
          null,
          fileFormat,
          ocrMode,
          ocrLanguage,
          ocrDpi,
          showHelp,
          null);
    }

    private static ParsedCommand combine(
        Path inputDir,
        Path outputFile,
        OcrMode ocrMode,
        String ocrLanguage,
        int ocrDpi,
        boolean showHelp) {
      return new ParsedCommand(
          CommandMode.COMBINE,
          null,
          inputDir,
          null,
          outputFile,
          null,
          PdfFileFormat.md,
          ocrMode,
          ocrLanguage,
          ocrDpi,
          showHelp,
          null);
    }

    private static ParsedCommand split(Path bundleFile, Path outputDir, boolean showHelp) {
      return new ParsedCommand(
          CommandMode.SPLIT,
          null,
          null,
          bundleFile,
          null,
          outputDir,
          PdfFileFormat.md,
          OcrMode.auto,
          "eng+rus",
          300,
          showHelp,
          null);
    }

    private ParsedCommand withResolvedExtractOptions(PdfExtractionOptions options) {
      return new ParsedCommand(
          mode,
          inputFile,
          inputDir,
          bundleFile,
          options.outputFile(),
          outputDir,
          options.fileFormat(),
          options.ocrMode(),
          options.ocrLanguage(),
          options.ocrDpi(),
          showHelp,
          options);
    }

    private ParsedCommand withResolvedCombineOutput(
        Path resolvedInputDir, Path resolvedOutputFile) {
      return new ParsedCommand(
          mode,
          inputFile,
          resolvedInputDir,
          bundleFile,
          resolvedOutputFile,
          outputDir,
          fileFormat,
          ocrMode,
          ocrLanguage,
          ocrDpi,
          showHelp,
          extractOptions);
    }

    private ParsedCommand withResolvedSplitOutput(Path resolvedBundleFile, Path resolvedOutputDir) {
      return new ParsedCommand(
          mode,
          inputFile,
          inputDir,
          resolvedBundleFile,
          outputFile,
          resolvedOutputDir,
          fileFormat,
          ocrMode,
          ocrLanguage,
          ocrDpi,
          showHelp,
          extractOptions);
    }

    private static String nextValue(String[] args, int index, String optionName) {
      if (index >= args.length) {
        throw new IllegalArgumentException("Missing value for option " + optionName);
      }
      return args[index];
    }

    private static String[] slice(String[] args, int start) {
      String[] result = new String[Math.max(0, args.length - start)];
      System.arraycopy(args, start, result, 0, result.length);
      return result;
    }
  }
}
