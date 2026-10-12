#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DIST_JAR_PATH="$SCRIPT_DIR/../dist/pdf-extractor-standalone.jar"
TARGET_JAR_PATH="$SCRIPT_DIR/../target/pdf-extractor-standalone-3.0.2.jar"
M2_REPO="${M2_REPO:-$HOME/.m2/repository}"

if [[ -f "$DIST_JAR_PATH" ]]; then
  exec java -Djava.awt.headless=true -jar "$DIST_JAR_PATH" "$@"
elif [[ -f "$TARGET_JAR_PATH" ]]; then
  CP="$TARGET_JAR_PATH"
  CP="$CP:$M2_REPO/org/apache/pdfbox/pdfbox/2.0.35/pdfbox-2.0.35.jar"
  CP="$CP:$M2_REPO/org/apache/pdfbox/fontbox/2.0.35/fontbox-2.0.35.jar"
  CP="$CP:$M2_REPO/commons-logging/commons-logging/1.2/commons-logging-1.2.jar"
  CP="$CP:$M2_REPO/net/sourceforge/tess4j/tess4j/5.19.0/tess4j-5.19.0.jar"
  CP="$CP:$M2_REPO/net/java/dev/jna/jna/5.18.1/jna-5.18.1.jar"
  CP="$CP:$M2_REPO/com/github/jai-imageio/jai-imageio-core/1.4.0/jai-imageio-core-1.4.0.jar"
  CP="$CP:$M2_REPO/com/github/jai-imageio/jai-imageio-jpeg2000/1.4.0/jai-imageio-jpeg2000-1.4.0.jar"
  CP="$CP:$M2_REPO/org/apache/pdfbox/pdfbox-tools/3.0.7/pdfbox-tools-3.0.7.jar"
  CP="$CP:$M2_REPO/org/apache/pdfbox/pdfbox-debugger/3.0.7/pdfbox-debugger-3.0.7.jar"
  CP="$CP:$M2_REPO/org/bouncycastle/bcpkix-jdk18on/1.83/bcpkix-jdk18on-1.83.jar"
  CP="$CP:$M2_REPO/org/bouncycastle/bcutil-jdk18on/1.83/bcutil-jdk18on-1.83.jar"
  CP="$CP:$M2_REPO/org/bouncycastle/bcprov-jdk18on/1.83/bcprov-jdk18on-1.83.jar"
  CP="$CP:$M2_REPO/info/picocli/picocli/4.7.7/picocli-4.7.7.jar"
  CP="$CP:$M2_REPO/org/apache/pdfbox/jbig2-imageio/3.0.4/jbig2-imageio-3.0.4.jar"
  CP="$CP:$M2_REPO/commons-io/commons-io/2.22.0/commons-io-2.22.0.jar"
  CP="$CP:$M2_REPO/net/sourceforge/lept4j/lept4j/1.24.0/lept4j-1.24.0.jar"
  CP="$CP:$M2_REPO/org/jboss/jboss-vfs/3.2.17.Final/jboss-vfs-3.2.17.Final.jar"
  CP="$CP:$M2_REPO/org/jboss/logging/jboss-logging/3.1.4.GA/jboss-logging-3.1.4.GA.jar"
  CP="$CP:$M2_REPO/org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar"
  exec java -Djava.awt.headless=true -cp "$CP" io.github.stepanbokarev.pdfextractor.PdfExtractorApplication "$@"
else
  echo "Jar not found in dist/ or target/" >&2
  echo "Build it first with mvn package or ./mvnw package" >&2
  exit 1
fi
