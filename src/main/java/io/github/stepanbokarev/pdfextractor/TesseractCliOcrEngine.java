package io.github.stepanbokarev.pdfextractor;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.imageio.ImageIO;

/** Local OCR bridge that shells out to an installed Tesseract binary. */
final class TesseractCliOcrEngine implements OcrEngine {

  private static final List<String> MAC_CANDIDATES =
      List.of("/opt/homebrew/bin/tesseract", "/usr/local/bin/tesseract");

  private final Optional<Path> executable;

  TesseractCliOcrEngine() {
    this.executable = locateExecutable();
  }

  @Override
  public String name() {
    return "tesseract-cli";
  }

  @Override
  public boolean isAvailable() {
    return executable.isPresent();
  }

  @Override
  public boolean supportsLanguage(String language) {
    return isAvailable();
  }

  @Override
  public String ocr(BufferedImage image, String language) throws IOException, InterruptedException {
    if (executable.isEmpty()) {
      throw new IOException("Local Tesseract executable was not found");
    }

    Path inputImage = Files.createTempFile("standalone-pdf-ocr-", ".png");
    try {
      ImageIO.write(image, "png", inputImage.toFile());
      List<String> command = new ArrayList<>();
      command.add(executable.get().toString());
      command.add(inputImage.toString());
      command.add("stdout");
      command.add("-l");
      command.add(language);
      command.add("--psm");
      command.add("3");
      command.add("-c");
      command.add("preserve_interword_spaces=1");
      command.add("txt");

      Process process = new ProcessBuilder(command).start();
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      String errorOutput =
          new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
      int exitCode = process.waitFor();
      if (exitCode != 0) {
        throw new IOException(
            "External tesseract exited with code "
                + exitCode
                + (errorOutput.isBlank() ? "" : " - " + errorOutput.trim()));
      }
      return output;
    } finally {
      Files.deleteIfExists(inputImage);
    }
  }

  private static Optional<Path> locateExecutable() {
    List<Path> candidates = new ArrayList<>();
    addEnvCandidate(candidates, "ADAPSTORY_TESSERACT_PATH");
    addEnvCandidate(candidates, "TESSERACT_PATH");
    addPathCandidates(candidates);

    String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
    if (osName.contains("mac")) {
      MAC_CANDIDATES.stream().map(Path::of).forEach(candidates::add);
    }
    if (osName.contains("win")) {
      addWindowsCandidates(candidates);
    }

    return candidates.stream()
        .filter(path -> Files.isRegularFile(path) && Files.isExecutable(path))
        .findFirst();
  }

  private static void addEnvCandidate(List<Path> candidates, String envName) {
    String value = System.getenv(envName);
    if (value != null && !value.isBlank()) {
      candidates.add(Path.of(value));
    }
  }

  private static void addPathCandidates(List<Path> candidates) {
    String pathEnv = System.getenv("PATH");
    if (pathEnv == null || pathEnv.isBlank()) {
      return;
    }
    boolean windows = isWindows();
    String executableName = windows ? "tesseract.exe" : "tesseract";
    for (String segment : pathEnv.split(java.io.File.pathSeparator)) {
      if (!segment.isBlank()) {
        candidates.add(Path.of(segment).resolve(executableName));
      }
    }
  }

  private static void addWindowsCandidates(List<Path> candidates) {
    addWindowsCandidate(candidates, System.getenv("ProgramFiles"));
    addWindowsCandidate(candidates, System.getenv("ProgramFiles(x86)"));
    String localAppData = System.getenv("LOCALAPPDATA");
    if (localAppData != null && !localAppData.isBlank()) {
      addWindowsCandidate(candidates, Path.of(localAppData).resolve("Programs").toString());
    }
  }

  private static void addWindowsCandidate(List<Path> candidates, String root) {
    if (root != null && !root.isBlank()) {
      candidates.add(Path.of(root).resolve("Tesseract-OCR").resolve("tesseract.exe"));
    }
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }
}
