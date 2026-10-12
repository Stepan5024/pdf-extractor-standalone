package io.github.stepanbokarev.pdfextractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Jpeg2000RenderingTest {

  // Synthetic fixture from cgohlke/imagecodecs-samples (CC BY 4.0):
  // https://github.com/cgohlke/imagecodecs-samples/blob/main/images/gray_u1.jp2
  private static final String JPEG2000_IMAGE_BASE64 =
      "AAAADGpQICANCocKAAAAFGZ0eXBqcDIgAAAAAGpwMiAAAAAtanAyaAAAABZpaGRyAAAAIAAAAB8AAQcH"
          + "AAAAAAAPY29scgEAAAAAABEAAAJJanAyY/9P/1EAKQAAAAAAHwAAACAAAAAAAAAAAAAAAB8AAAAgAAAA"
          + "AAAAAAAAAQcBAf9SAAwAAAABAAEEBAAB/1wAB0BASEhQ/2QAJQABQ3JlYXRlZCBieSBPcGVuSlBFRyB2"
          + "ZXJzaW9uIDIuNS40/5AACgAAAAAB1AAB/5PPttYJRTy3bZ/I64/aFfGTcB4AjqiaiXGTvtrr5hvtmbUr"
          + "GZYWiTDzgNWTuzLZ5niNYfbO17aBcYfzvurdPwLUpgFV3xL1+CX7XeViNe3VvrQB/jRRCgEFERvfD3/"
          + "iyo7C6IOCvvTC+iB276skcs1745HTKJvwqyOik5Wzbt7XdkwUdTx/yG3poMdt/dEiNdpEqXE7aLg4IN"
          + "io282b12SGC6jzwpQrcUdUfbzMdAjBN2BWulrd7UKYear0AgvsZyBlVENwHN9DW6JEDKvOGM6uftzuw"
          + "CqvnGd/wHw6hB89igDtiAltD7K3z3ga2JDhwQWUtTihQPuh1/kdXMWBE5f45NIqBOZ22Wup6f79Cy6"
          + "fEL4EA/ApvP5+/KC4wFrosZLcjq33009okryHrTLQNzNbkDtfpjD0qQLWYpYZ3hRz0gipL5QHVKK02U"
          + "tQtZzYNMsTRmmjK+/hTddprEiw77QwG9hdE7yDC29j5b7ohHtJ8nsoG9Na2+Ash6zLga0NOcR29jN1"
          + "px0uPjKM2hq/UvNdtpP4N39CYiqAqthGMIr9FkQYO+cevNbOm8Cb74lf05g4FT2o7kuka0/ZhIJgaoP"
          + "majLjwbaS/9k=";

  @TempDir Path tempDir;

  @Test
  void rendersPdfContainingJpeg2000Image() throws Exception {
    Path inputPdf = tempDir.resolve("jpeg2000-scan.pdf");
    createJpeg2000Pdf(inputPdf);

    try (PDDocument document = PDDocument.load(inputPdf.toFile())) {
      BufferedImage rendered = new PDFRenderer(document).renderImageWithDPI(0, 72);
      int[] pixels =
          rendered.getRGB(
              0, 0, rendered.getWidth(), rendered.getHeight(), null, 0, rendered.getWidth());
      assertThat(Arrays.stream(pixels).distinct().count()).isGreaterThan(1);
    }
  }

  private static void createJpeg2000Pdf(Path target) throws Exception {
    byte[] imageBytes = Base64.getDecoder().decode(JPEG2000_IMAGE_BASE64);
    try (PDDocument document = new PDDocument()) {
      PDPage page = new PDPage(new PDRectangle(32, 31));
      document.addPage(page);
      PDImageXObject image =
          new PDImageXObject(
              document,
              new ByteArrayInputStream(imageBytes),
              COSName.JPX_DECODE,
              32,
              31,
              8,
              PDDeviceGray.INSTANCE);
      try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
        contentStream.drawImage(image, 0, 0, 32, 31);
      }
      document.save(target.toFile());
    }
  }
}
