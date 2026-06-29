package io.github.stepanbokarev.pdfextractor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EmbeddedTesseractOcrEngineTest {

  @Test
  void exposesBundledLanguagesWithoutSystemTesseract() {
    EmbeddedTesseractOcrEngine engine = new EmbeddedTesseractOcrEngine();

    assertThat(engine.isAvailable()).isTrue();
    assertThat(engine.supportsLanguage("eng")).isTrue();
    assertThat(engine.supportsLanguage("eng+rus")).isTrue();
    assertThat(engine.supportsLanguage("eng+deu")).isFalse();
  }
}
