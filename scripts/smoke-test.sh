#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR_PATH="${1:-$PROJECT_DIR/dist/pdf-extractor-standalone.jar}"
JAVA_CMD="${JAVA_CMD:-java}"
mkdir -p "$PROJECT_DIR/target"
SMOKE_DIR="$(mktemp -d "$PROJECT_DIR/target/font-smoke.XXXXXXXX")"
mkdir -p "$SMOKE_DIR/runtime"

"$JAVA_CMD" -Djava.awt.headless=true -Djava.io.tmpdir="$SMOKE_DIR/runtime" \
  -cp "$PROJECT_DIR/target/test-classes:$JAR_PATH" \
  io.github.stepanbokarev.pdfextractor.UnnamedFontRegressionTest "$SMOKE_DIR/pdfs"
"$JAVA_CMD" -Djava.awt.headless=true -Djava.io.tmpdir="$SMOKE_DIR/runtime" \
  -jar "$JAR_PATH" combine "$SMOKE_DIR/pdfs" \
  --output-file "$SMOKE_DIR/combined.md" --ocr-mode off
grep -Fq 'PATH: nested/unnamed.md' "$SMOKE_DIR/combined.md"
grep -Fq 'Unnamed font text.' "$SMOKE_DIR/combined.md"
grep -Fq 'Named font text.' "$SMOKE_DIR/combined.md"
"$JAVA_CMD" -Djava.awt.headless=true -Djava.io.tmpdir="$SMOKE_DIR/runtime" \
  -jar "$JAR_PATH" split "$SMOKE_DIR/combined.md" --output-dir "$SMOKE_DIR/restored"
grep -Fq 'Unnamed font text.' "$SMOKE_DIR/restored/nested/unnamed.md"
printf 'PASS: packaged JAR combines an unnamed Type 3 font and restores its text.\n'
