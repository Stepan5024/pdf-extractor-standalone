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

"$JAVA_CMD" -Djava.awt.headless=true -Djava.io.tmpdir="$SMOKE_DIR/runtime" \
  -cp "$PROJECT_DIR/target/test-classes:$JAR_PATH" \
  io.github.stepanbokarev.pdfextractor.UnmappedGlyphFixture "$SMOKE_DIR/recovery"
"$JAVA_CMD" -Djava.awt.headless=true -Djava.io.tmpdir="$SMOKE_DIR/runtime" \
  -jar "$JAR_PATH" combine "$SMOKE_DIR/recovery" \
  --output-file "$SMOKE_DIR/recovered.md"
grep -Fq 'RECOVER VISIBLE TEXT 2468' "$SMOKE_DIR/recovered.md"
grep -Fq 'Ordinary mapped text is readable and must be preserved.' "$SMOKE_DIR/recovered.md"
if "$JAVA_CMD" -Djava.awt.headless=true -Djava.io.tmpdir="$SMOKE_DIR/runtime" \
  -jar "$JAR_PATH" combine "$SMOKE_DIR/recovery" \
  --output-file "$SMOKE_DIR/rejected.md" --ocr-mode off > "$SMOKE_DIR/rejected.log" 2>&1; then
  printf 'FAIL: missing Unicode mappings were silently accepted with OCR disabled.\n' >&2
  exit 1
else
  FAILURE_STATUS=$?
  test "$FAILURE_STATUS" -eq 3
fi
test ! -e "$SMOKE_DIR/rejected.md"
grep -Fq 'Unicode' "$SMOKE_DIR/rejected.log"
grep -Fq 'OCR' "$SMOKE_DIR/rejected.log"
printf 'PASS: packaged JAR recovers visible Unicode text with automatic OCR and refuses silent loss.\n'
