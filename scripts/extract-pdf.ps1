param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$rootDir = Split-Path -Parent $scriptDir
$distJarPath = Join-Path $rootDir 'dist/pdf-extractor-standalone.jar'
$targetJarPath = Join-Path $rootDir 'target/pdf-extractor-standalone-3.0.3.jar'

if ($env:M2_REPO) {
    $m2 = $env:M2_REPO
} elseif ($env:USERPROFILE) {
    $m2 = Join-Path $env:USERPROFILE '.m2/repository'
} else {
    $m2 = Join-Path $HOME '.m2/repository'
}

if (Test-Path $distJarPath) {
    & java '-Djava.awt.headless=true' -jar $distJarPath @Arguments
    exit $LASTEXITCODE
}

if (Test-Path $targetJarPath) {
    $cpEntries = @(
        $targetJarPath,
        (Join-Path $m2 'org/apache/pdfbox/pdfbox/2.0.35/pdfbox-2.0.35.jar'),
        (Join-Path $m2 'org/apache/pdfbox/fontbox/2.0.35/fontbox-2.0.35.jar'),
        (Join-Path $m2 'commons-logging/commons-logging/1.2/commons-logging-1.2.jar'),
        (Join-Path $m2 'net/sourceforge/tess4j/tess4j/5.19.0/tess4j-5.19.0.jar'),
        (Join-Path $m2 'net/java/dev/jna/jna/5.18.1/jna-5.18.1.jar'),
        (Join-Path $m2 'com/github/jai-imageio/jai-imageio-core/1.4.0/jai-imageio-core-1.4.0.jar'),
        (Join-Path $m2 'com/github/jai-imageio/jai-imageio-jpeg2000/1.4.0/jai-imageio-jpeg2000-1.4.0.jar'),
        (Join-Path $m2 'org/apache/pdfbox/pdfbox-tools/3.0.7/pdfbox-tools-3.0.7.jar'),
        (Join-Path $m2 'org/apache/pdfbox/pdfbox-debugger/3.0.7/pdfbox-debugger-3.0.7.jar'),
        (Join-Path $m2 'org/bouncycastle/bcpkix-jdk18on/1.83/bcpkix-jdk18on-1.83.jar'),
        (Join-Path $m2 'org/bouncycastle/bcutil-jdk18on/1.83/bcutil-jdk18on-1.83.jar'),
        (Join-Path $m2 'org/bouncycastle/bcprov-jdk18on/1.83/bcprov-jdk18on-1.83.jar'),
        (Join-Path $m2 'info/picocli/picocli/4.7.7/picocli-4.7.7.jar'),
        (Join-Path $m2 'org/apache/pdfbox/jbig2-imageio/3.0.4/jbig2-imageio-3.0.4.jar'),
        (Join-Path $m2 'commons-io/commons-io/2.22.0/commons-io-2.22.0.jar'),
        (Join-Path $m2 'net/sourceforge/lept4j/lept4j/1.24.0/lept4j-1.24.0.jar'),
        (Join-Path $m2 'org/jboss/jboss-vfs/3.2.17.Final/jboss-vfs-3.2.17.Final.jar'),
        (Join-Path $m2 'org/jboss/logging/jboss-logging/3.1.4.GA/jboss-logging-3.1.4.GA.jar'),
        (Join-Path $m2 'org/slf4j/slf4j-api/2.0.18/slf4j-api-2.0.18.jar')
    )

    $classpath = [string]::Join([IO.Path]::PathSeparator, $cpEntries)
    & java '-Djava.awt.headless=true' -cp $classpath io.github.stepanbokarev.pdfextractor.PdfExtractorApplication @Arguments
    exit $LASTEXITCODE
}

Write-Error 'Jar not found in dist\ or target\. Build it first with mvn package or mvnw.cmd package.'
exit 1
