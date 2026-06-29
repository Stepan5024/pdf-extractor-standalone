@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
set "DIST_JAR_PATH=%SCRIPT_DIR%..\dist\pdf-extractor-standalone.jar"
set "TARGET_JAR_PATH=%SCRIPT_DIR%..\target\pdf-extractor-standalone-1.0.0.jar"
if defined M2_REPO (
  set "M2=%M2_REPO%"
) else (
  set "M2=%USERPROFILE%\.m2\repository"
)

if exist "%DIST_JAR_PATH%" (
  java -jar "%DIST_JAR_PATH%" %*
  exit /b %ERRORLEVEL%
) else if exist "%TARGET_JAR_PATH%" (
  set "CP=%TARGET_JAR_PATH%;%M2%\org\apache\pdfbox\pdfbox\2.0.35\pdfbox-2.0.35.jar;%M2%\org\apache\pdfbox\fontbox\2.0.35\fontbox-2.0.35.jar;%M2%\commons-logging\commons-logging\1.2\commons-logging-1.2.jar;%M2%\net\sourceforge\tess4j\tess4j\5.19.0\tess4j-5.19.0.jar;%M2%\net\java\dev\jna\jna\5.18.1\jna-5.18.1.jar;%M2%\com\github\jai-imageio\jai-imageio-core\1.4.0\jai-imageio-core-1.4.0.jar;%M2%\org\apache\pdfbox\pdfbox-tools\3.0.7\pdfbox-tools-3.0.7.jar;%M2%\org\apache\pdfbox\pdfbox-debugger\3.0.7\pdfbox-debugger-3.0.7.jar;%M2%\org\bouncycastle\bcpkix-jdk18on\1.83\bcpkix-jdk18on-1.83.jar;%M2%\org\bouncycastle\bcutil-jdk18on\1.83\bcutil-jdk18on-1.83.jar;%M2%\org\bouncycastle\bcprov-jdk18on\1.83\bcprov-jdk18on-1.83.jar;%M2%\info\picocli\picocli\4.7.7\picocli-4.7.7.jar;%M2%\org\apache\pdfbox\jbig2-imageio\3.0.4\jbig2-imageio-3.0.4.jar;%M2%\commons-io\commons-io\2.22.0\commons-io-2.22.0.jar;%M2%\net\sourceforge\lept4j\lept4j\1.24.0\lept4j-1.24.0.jar;%M2%\org\jboss\jboss-vfs\3.2.17.Final\jboss-vfs-3.2.17.Final.jar;%M2%\org\jboss\logging\jboss-logging\3.1.4.GA\jboss-logging-3.1.4.GA.jar;%M2%\org\slf4j\slf4j-api\2.0.18\slf4j-api-2.0.18.jar"
  java -cp "%CP%" io.github.stepanbokarev.pdfextractor.PdfExtractorApplication %*
  exit /b %ERRORLEVEL%
) else (
  echo Jar not found in dist\ or target\
  echo Build it first with mvn package or mvnw.cmd package
  exit /b 1
)
